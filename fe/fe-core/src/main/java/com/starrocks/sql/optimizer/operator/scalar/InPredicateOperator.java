// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.
package com.starrocks.sql.optimizer.operator.scalar;

import com.starrocks.sql.optimizer.operator.OperatorType;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class InPredicateOperator extends PredicateOperator {
    private final boolean isNotIn;

    public InPredicateOperator(ScalarOperator... arguments) {
        super(OperatorType.IN, arguments);
        this.isNotIn = false;
    }

    public InPredicateOperator(boolean isNotIn, ScalarOperator... arguments) {
        super(OperatorType.IN, arguments);
        this.isNotIn = isNotIn;
    }

    public InPredicateOperator(boolean isNotIn, List<ScalarOperator> arguments) {
        super(OperatorType.IN, arguments);
        this.isNotIn = isNotIn;
    }

    public boolean isNotIn() {
        return isNotIn;
    }

    public boolean allValuesMatch(Predicate<? super ScalarOperator> lambda) {
        return getChildren().stream().skip(1).allMatch(lambda);
    }

    public boolean hasAnyNullValues() {
        return getChildren().stream().skip(1)
                .anyMatch(child -> (child.isConstantRef() && ((ConstantOperator) child).isNull()));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getChild(0)).append(" ");
        if (isNotIn) {
            sb.append("NOT ");
        }

        sb.append("IN (");
        sb.append(getChildren().stream().skip(1).map(ScalarOperator::toString).collect(Collectors.joining(", ")));
        sb.append(")");
        return sb.toString();
    }

    @Override
    public <R, C> R accept(ScalarOperatorVisitor<R, C> visitor, C context) {
        return visitor.visitInPredicate(this, context);
    }

    @Override
    public String debugString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getChild(0).debugString()).append(" ");
        if (isNotIn) {
            sb.append("NOT ");
        }

        sb.append("IN (");
        sb.append(getChildren().stream().skip(1).map(ScalarOperator::debugString).collect(Collectors.joining(", ")));
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        InPredicateOperator that = (InPredicateOperator) o;
        return isNotIn == that.isNotIn;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), isNotIn);
    }

    @Override
    public boolean isStrictPredicate() {
        return getChild(0).isColumnRefOrCast();
    }
}
