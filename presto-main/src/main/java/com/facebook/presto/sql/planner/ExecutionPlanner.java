package com.facebook.presto.sql.planner;

import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.metadata.FunctionHandle;
import com.facebook.presto.operator.AggregationOperator;
import com.facebook.presto.operator.FilterAndProjectOperator;
import com.facebook.presto.operator.FilterFunction;
import com.facebook.presto.operator.FilterFunctions;
import com.facebook.presto.operator.HashAggregationOperator;
import com.facebook.presto.operator.LimitOperator;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.operator.ProjectionFunction;
import com.facebook.presto.operator.ProjectionFunctions;
import com.facebook.presto.operator.TopNOperator;
import com.facebook.presto.operator.aggregation.AggregationFunction;
import com.facebook.presto.operator.aggregation.AggregationFunctionStep;
import com.facebook.presto.operator.aggregation.AggregationFunctions;
import com.facebook.presto.operator.aggregation.Input;
import com.facebook.presto.split.DataStreamProvider;
import com.facebook.presto.split.Split;
import com.facebook.presto.sql.compiler.AnalysisResult;
import com.facebook.presto.sql.compiler.SessionMetadata;
import com.facebook.presto.sql.compiler.Symbol;
import com.facebook.presto.sql.compiler.Type;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.tuple.FieldOrderedTupleComparator;
import com.facebook.presto.tuple.TupleInfo;
import com.facebook.presto.tuple.TupleReadable;
import com.facebook.presto.util.IterableTransformer;
import com.facebook.presto.util.MoreFunctions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import javax.inject.Provider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Functions.forMap;

public class ExecutionPlanner
{
    private final SessionMetadata metadata;
    private final DataStreamProvider dataProvider;

    private final Map<Symbol, Type> types;
    private final Split split;

    public ExecutionPlanner(SessionMetadata metadata, DataStreamProvider dataProvider, Map<Symbol, Type> types, Split split)
    {
        this.metadata = metadata;
        this.types = types;
        this.dataProvider = dataProvider;
        this.split = split;
    }

    public Operator plan(PlanNode plan)
    {
        if (plan instanceof TableScan) {
            return createTableScan((TableScan) plan);
        }
        else if (plan instanceof ProjectNode) {
            return createProjectNode((ProjectNode) plan);
        }
        else if (plan instanceof FilterNode) {
            return createFilterNode((FilterNode) plan);
        }
        else if (plan instanceof OutputPlan) {
            return createOutputPlan((OutputPlan) plan);
        }
        else if (plan instanceof AggregationNode) {
            return createAggregationNode((AggregationNode) plan);
        }
        else if (plan instanceof LimitNode) {
            return createLimitNode((LimitNode) plan);
        }
        else if (plan instanceof TopNNode) {
            return createTopNNode((TopNNode) plan);
        }

        throw new UnsupportedOperationException("not yet implemented: " + plan.getClass().getName());
    }

    private Operator createOutputPlan(OutputPlan node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(node.getSource());

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<Symbol> resultSymbols = Lists.transform(node.getColumnNames(), forMap(node.getAssignments()));
        if (resultSymbols.equals(source.getOutputSymbols())) {
            // no need for a projection -- the output matches the result of the underlying operator
            return sourceOperator;
        }

        List<ProjectionFunction> projections = new ArrayList<>();
        for (Symbol symbol : resultSymbols) {
            ProjectionFunction function = new InterpretedProjectionFunction(types.get(symbol),
                    new QualifiedNameReference(symbol.toQualifiedName()),
                    symbolToChannelMappings,
                    types);
            projections.add(function);
        }

        return new FilterAndProjectOperator(sourceOperator, FilterFunctions.TRUE_FUNCTION, projections);
    }

    private Operator createTopNNode(TopNNode node)
    {
        Preconditions.checkArgument(node.getOrderBy().size() == 1, "Order by multiple fields not yet supported");
        Symbol orderBySymbol = Iterables.getOnlyElement(node.getOrderBy());

        PlanNode source = node.getSource();

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getOutputSymbols().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            ProjectionFunction function = ProjectionFunctions.singleColumn(types.get(symbol).getRawType(), symbolToChannelMappings.get(symbol), 0);
            projections.add(function);
        }

        Ordering<TupleReadable> ordering = Ordering.from(FieldOrderedTupleComparator.INSTANCE);
        if (node.getOrderings().get(orderBySymbol) == SortItem.Ordering.ASCENDING) {
            ordering = ordering.reverse();
        }

        return new TopNOperator(plan(source), (int) node.getCount(), symbolToChannelMappings.get(orderBySymbol), projections, ordering);
    }

    private Operator createLimitNode(LimitNode node)
    {
        PlanNode source = node.getSource();
        return new LimitOperator(plan(source), node.getCount());
    }

    private Operator createAggregationNode(AggregationNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(source);

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<Provider<AggregationFunctionStep>> aggregationFunctions = new ArrayList<>();
        for (Map.Entry<Symbol, FunctionCall> entry : node.getAggregations().entrySet()) {
            List<Input> arguments = new ArrayList<>();
            for (Expression argument : entry.getValue().getArguments()) {
                int channel = symbolToChannelMappings.get(Symbol.fromQualifiedName(((QualifiedNameReference) argument).getName()));
                int field = 0; // TODO: support composite channels

                arguments.add(new Input(channel, field));
            }

            FunctionHandle functionHandle = node.getFunctions().get(entry.getKey());
            Provider<AggregationFunction> boundFunction = metadata.getFunction(functionHandle).bind(arguments);

            Provider<AggregationFunctionStep> aggregation;
            switch (node.getStep()) {
                case PARTIAL:
                    aggregation = AggregationFunctions.partialAggregation(boundFunction);
                    break;
                case FINAL:
                    aggregation = AggregationFunctions.finalAggregation(boundFunction);
                    break;
                case SINGLE:
                    aggregation = AggregationFunctions.singleNodeAggregation(boundFunction);
                    break;
                default:
                    throw new UnsupportedOperationException("not yet implemented: " + node.getStep());
            }

            aggregationFunctions.add(aggregation);
        }

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getOutputSymbols().size(); ++i) {
            Symbol symbol = node.getOutputSymbols().get(i);
            projections.add(ProjectionFunctions.singleColumn(types.get(symbol).getRawType(), i, 0));
        }

        if (node.getGroupBy().isEmpty()) {
            return new AggregationOperator(sourceOperator, aggregationFunctions, projections);
        }

        Preconditions.checkArgument(node.getGroupBy().size() <= 1, "Only single GROUP BY key supported at this time");
        Symbol groupBySymbol = Iterables.getOnlyElement(node.getGroupBy());
        return new HashAggregationOperator(sourceOperator, symbolToChannelMappings.get(groupBySymbol), aggregationFunctions, projections);
    }

    private Operator createFilterNode(FilterNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(source);

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        FilterFunction filter = new InterpretedFilterFunction(node.getPredicate(), symbolToChannelMappings, types);

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getOutputSymbols().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            ProjectionFunction function = ProjectionFunctions.singleColumn(types.get(symbol).getRawType(), symbolToChannelMappings.get(symbol), 0);
            projections.add(function);
        }

        return new FilterAndProjectOperator(sourceOperator, filter, projections);
    }

    private Operator createProjectNode(final ProjectNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(node.getSource());

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getExpressions().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            Expression expression = node.getExpressions().get(i);
            ProjectionFunction function = new InterpretedProjectionFunction(types.get(symbol), expression, symbolToChannelMappings, types);
            projections.add(function);
        }

        return new FilterAndProjectOperator(sourceOperator, FilterFunctions.TRUE_FUNCTION, projections);
    }

    private Operator createTableScan(TableScan node)
    {
        List<ColumnHandle> columns = IterableTransformer.on(node.getAssignments().entrySet())
                .orderBy(Ordering.explicit(node.getOutputSymbols()).onResultOf(MoreFunctions.<Symbol, ColumnHandle>keyGetter()))
                .transform(MoreFunctions.<Symbol, ColumnHandle>valueGetter())
                .list();

        return dataProvider.createDataStream(split, columns);
    }

    public static TupleInfo toTupleInfo(AnalysisResult analysis, Iterable<Symbol> symbols)
    {
        ImmutableList.Builder<TupleInfo.Type> types = ImmutableList.builder();
        for (Symbol symbol : symbols) {
            types.add(analysis.getType(symbol).getRawType());
        }
        return new TupleInfo(types.build());
    }

    private Map<Symbol, Integer> mapSymbolsToChannels(List<Symbol> outputs)
    {
        Map<Symbol, Integer> symbolToChannelMappings = new HashMap<>();
        for (int i = 0; i < outputs.size(); i++) {
            symbolToChannelMappings.put(outputs.get(i), i);
        }
        return symbolToChannelMappings;
    }
}
