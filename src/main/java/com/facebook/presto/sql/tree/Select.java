package com.facebook.presto.sql.tree;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class Select
{
    private final boolean distinct;
    private final List<Expression> selectItems;

    public Select(boolean distinct, List<Expression> selectItems)
    {
        this.distinct = distinct;
        this.selectItems = ImmutableList.copyOf(checkNotNull(selectItems, "selectItems"));
    }

    public boolean isDistinct()
    {
        return distinct;
    }

    public List<Expression> getSelectItems()
    {
        return selectItems;
    }

    @Override
    public String toString()
    {
        return Objects.toStringHelper(this)
                .add("distinct", distinct)
                .add("selectItems", selectItems)
                .omitNullValues()
                .toString();
    }
}