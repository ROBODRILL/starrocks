// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.ColocateTableIndex;
import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.DistributionProperty;
import com.starrocks.sql.optimizer.base.DistributionSpec;
import com.starrocks.sql.optimizer.base.HashDistributionDesc;
import com.starrocks.sql.optimizer.base.HashDistributionSpec;
import com.starrocks.sql.optimizer.base.OrderSpec;
import com.starrocks.sql.optimizer.base.Ordering;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.base.SortProperty;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalAssertOneRowOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalEsScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalExceptOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalFilterOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHashAggregateOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHashJoinOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHiveScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalIntersectOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalMysqlScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalProjectOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalRepeatOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalSchemaScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalTableFunctionOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalTopNOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalUnionOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalValuesOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalWindowOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.rule.transformation.JoinPredicateUtils;
import com.starrocks.sql.optimizer.task.TaskContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.starrocks.sql.optimizer.rule.transformation.JoinPredicateUtils.getEqConj;
import static com.starrocks.sql.optimizer.rule.transformation.JoinPredicateUtils.isColumnToColumnBinaryPredicate;

/**
 * Get output and child input property for one physical operator
 * <p>
 * During query optimization, the optimizer looks for a physical plan that
 * satisfies a number of requirements. These requirements may be specified
 * on the query level such as an ORDER BY clause that specifies a required
 * sort order to be satisfied by query results.
 * Alternatively, the requirements may be triggered when
 * optimizing a plan subtree. For example, a Hash Join operator may
 * require its children to have hash distributions aligned with the
 * columns mentioned in join condition.
 * <p>
 * In either case, each operator receives requirements from the parent and
 * combines these requirements with local requirements to generate new
 * requirements (which may be empty) from each of its children.
 */
public class ChildPropertyDeriver extends OperatorVisitor<Void, ExpressionContext> {
    private PhysicalPropertySet requirements;
    private List<Pair<PhysicalPropertySet, List<PhysicalPropertySet>>> outputInputProps;
    private final TaskContext taskContext;
    private final OptimizerContext context;

    public ChildPropertyDeriver(TaskContext taskContext) {
        this.taskContext = taskContext;
        this.context = taskContext.getOptimizerContext();
    }

    public List<Pair<PhysicalPropertySet, List<PhysicalPropertySet>>> getOutputInputProps(
            PhysicalPropertySet requirements,
            GroupExpression groupExpression) {
        this.requirements = requirements;

        outputInputProps = Lists.newArrayList();
        groupExpression.getOp().accept(this, new ExpressionContext(groupExpression));
        return outputInputProps;
    }

    private PhysicalPropertySet distributeRequirements() {
        return new PhysicalPropertySet(requirements.getDistributionProperty());
    }

    @Override
    public Void visitOperator(Operator node, ExpressionContext context) {
        return null;
    }

    @Override
    public Void visitPhysicalHashJoin(PhysicalHashJoinOperator node, ExpressionContext context) {
        String hint = node.getJoinHint();

        // 1 For broadcast join
        PhysicalPropertySet rightBroadcastProperty =
                new PhysicalPropertySet(new DistributionProperty(DistributionSpec.createReplicatedDistributionSpec()));

        LogicalOperator leftChild = (LogicalOperator) context.getChildOperator(0);
        LogicalOperator rightChild = (LogicalOperator) context.getChildOperator(1);
        // If child has limit, we need to gather data to one instance
        if (leftChild.hasLimit() || rightChild.hasLimit()) {
            if (leftChild.hasLimit()) {
                outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                        Lists.newArrayList(createLimitGatherProperty(leftChild.getLimit()), rightBroadcastProperty)));
            } else {
                outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                        Lists.newArrayList(new PhysicalPropertySet(), rightBroadcastProperty)));
            }
            // If child has limit, only do broadcast join
            return visitJoinRequirements(node, context);
        } else {
            outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                    Lists.newArrayList(new PhysicalPropertySet(), rightBroadcastProperty)));
        }

        ColumnRefSet leftChildColumns = context.getChildOutputColumns(0);
        ColumnRefSet rightChildColumns = context.getChildOutputColumns(1);
        List<BinaryPredicateOperator> equalOnPredicate =
                getEqConj(leftChildColumns, rightChildColumns, Utils.extractConjuncts(node.getJoinPredicate()));

        // Cross join only support broadcast join
        if (node.getJoinType().isCrossJoin() || JoinOperator.NULL_AWARE_LEFT_ANTI_JOIN.equals(node.getJoinType())
                || (node.getJoinType().isInnerJoin() && equalOnPredicate.isEmpty())
                || "BROADCAST".equalsIgnoreCase(hint)) {
            return visitJoinRequirements(node, context);
        }

        if (node.getJoinType().isRightJoin() || node.getJoinType().isFullOuterJoin()
                || "SHUFFLE".equalsIgnoreCase(hint)) {
            outputInputProps.clear();
        }

        // 2 For shuffle join
        List<Integer> leftOnPredicateColumns = new ArrayList<>();
        List<Integer> rightOnPredicateColumns = new ArrayList<>();
        JoinPredicateUtils.getJoinOnPredicatesColumns(equalOnPredicate, leftChildColumns, rightChildColumns,
                leftOnPredicateColumns, rightOnPredicateColumns);
        Preconditions.checkState(leftOnPredicateColumns.size() == rightOnPredicateColumns.size());

        HashDistributionSpec leftDistribution = DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(leftOnPredicateColumns, HashDistributionDesc.SourceType.SHUFFLE_JOIN));
        HashDistributionSpec rightDistribution = DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(rightOnPredicateColumns, HashDistributionDesc.SourceType.SHUFFLE_JOIN));

        PhysicalPropertySet leftInputProperty = createPropertySetByDistribution(leftDistribution);
        PhysicalPropertySet rightInputProperty = createPropertySetByDistribution(rightDistribution);

        outputInputProps
                .add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(leftInputProperty, rightInputProperty)));

        // Respect use join hint
        if ("SHUFFLE".equalsIgnoreCase(hint)) {
            return visitJoinRequirements(node, context);
        }

        // Colocate join and bucket shuffle join only support column to column binary predicate
        if (equalOnPredicate.stream().anyMatch(p -> !isColumnToColumnBinaryPredicate(p))) {
            return visitJoinRequirements(node, context);
        }

        // 3 For colocate join
        if (!"BUCKET".equalsIgnoreCase(hint)) {
            tryColocate(leftDistribution, rightDistribution);
        }

        // 4 For bucket shuffle join
        tryBucketShuffle(node, leftDistribution, rightDistribution);

        // 5 resolve requirements
        return visitJoinRequirements(node, context);
    }

    /*
     * Colocate will required children support local properties by topdown
     * All shuffle columns(predicate columns) must come from one table
     * Can't support case such as (The predicate columns combinations is N*N):
     *          JOIN(s1.A = s3.A AND s2.A = s4.A)
     *             /            \
     *          JOIN           JOIN
     *         /    \         /    \
     *        s1    s2       s3     s4
     *
     * support case:
     *          JOIN(s1.A = s3.A AND s1.B = s3.B)
     *             /            \
     *          JOIN           JOIN
     *         /    \         /    \
     *        s1    s2       s3     s4
     *
     * */
    private void tryColocate(HashDistributionSpec leftShuffleDistribution,
                             HashDistributionSpec rightShuffleDistribution) {
        if (Config.disable_colocate_join || ConnectContext.get().getSessionVariable().isDisableColocateJoin()) {
            return;
        }

        Optional<LogicalOlapScanOperator> leftTable = findLogicalOlapScanOperator(leftShuffleDistribution);
        if (!leftTable.isPresent()) {
            return;
        }

        LogicalOlapScanOperator left = leftTable.get();

        Optional<LogicalOlapScanOperator> rightTable = findLogicalOlapScanOperator(rightShuffleDistribution);
        if (!rightTable.isPresent()) {
            return;
        }

        LogicalOlapScanOperator right = rightTable.get();
        ColocateTableIndex colocateIndex = Catalog.getCurrentColocateIndex();

        // join self
        if (left.getTable().getId() == right.getTable().getId() &&
                !colocateIndex.isSameGroup(left.getTable().getId(), right.getTable().getId())) {
            if (!left.getSelectedPartitionId().equals(right.getSelectedPartitionId())
                    || left.getSelectedPartitionId().size() > 1) {
                return;
            }

            PhysicalPropertySet rightLocalProperty = createPropertySetByDistribution(
                    createLocalByByHashColumns(rightShuffleDistribution.getShuffleColumns()));
            PhysicalPropertySet leftLocalProperty = createPropertySetByDistribution(
                    createLocalByByHashColumns(leftShuffleDistribution.getShuffleColumns()));

            outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                    Lists.newArrayList(leftLocalProperty, rightLocalProperty)));
        } else {
            // colocate group
            if (!colocateIndex.isSameGroup(left.getTable().getId(), right.getTable().getId())) {
                return;
            }

            ColocateTableIndex.GroupId groupId = colocateIndex.getGroup(left.getTable().getId());
            if (colocateIndex.isGroupUnstable(groupId)) {
                return;
            }

            HashDistributionSpec leftScanDistribution = left.getDistributionSpec();
            HashDistributionSpec rightScanDistribution = right.getDistributionSpec();

            Preconditions.checkState(leftScanDistribution.getShuffleColumns().size() ==
                    rightScanDistribution.getShuffleColumns().size());

            if (!leftShuffleDistribution.getShuffleColumns().containsAll(leftScanDistribution.getShuffleColumns())) {
                return;
            }

            if (!rightShuffleDistribution.getShuffleColumns().containsAll(rightScanDistribution.getShuffleColumns())) {
                return;
            }

            // check orders of predicate columns is right
            // check predicate columns is satisfy bucket hash columns
            for (int i = 0; i < leftScanDistribution.getShuffleColumns().size(); i++) {
                int leftScanColumnId = leftScanDistribution.getShuffleColumns().get(i);
                int leftIndex = leftShuffleDistribution.getShuffleColumns().indexOf(leftScanColumnId);

                int rightScanColumnId = rightScanDistribution.getShuffleColumns().get(i);
                int rightIndex = rightShuffleDistribution.getShuffleColumns().indexOf(rightScanColumnId);

                if (leftIndex != rightIndex) {
                    return;
                }
            }

            PhysicalPropertySet rightLocalProperty = createPropertySetByDistribution(
                    createLocalByByHashColumns(rightScanDistribution.getShuffleColumns()));
            PhysicalPropertySet leftLocalProperty = createPropertySetByDistribution(
                    createLocalByByHashColumns(leftScanDistribution.getShuffleColumns()));

            outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                    Lists.newArrayList(leftLocalProperty, rightLocalProperty)));
        }
    }

    /*
     * Bucket-shuffle will required left-children support local properties by topdown
     * All shuffle columns(predicate columns) must come from one table
     * Can't support case such as (The predicate columns combinations is N*N):
     *          JOIN(s1.A = s3.A AND s2.A = s4.A)
     *             /            \
     *          JOIN           JOIN
     *         /    \         /    \
     *        s1    s2       s3     s4
     *
     * support case:
     *          JOIN(s1.A = s3.A AND s1.B = s3.B)
     *             /            \
     *          JOIN           JOIN
     *         /    \         /    \
     *        s1    s2       s3     s4
     *
     * */
    private void tryBucketShuffle(PhysicalHashJoinOperator node, HashDistributionSpec leftShuffleDistribution,
                                  HashDistributionSpec rightShuffleDistribution) {
        JoinOperator nodeJoinType = node.getJoinType();
        if (nodeJoinType.isCrossJoin()) {
            return;
        }

        Optional<LogicalOlapScanOperator> leftTable = findLogicalOlapScanOperator(leftShuffleDistribution);

        if (!leftTable.isPresent()) {
            return;
        }

        LogicalOlapScanOperator left = leftTable.get();

        // Could only do bucket shuffle when partition size is 1, less 1 will case coordinator throw bugs
        if (left.getSelectedPartitionId().size() != 1) {
            return;
        }

        HashDistributionSpec leftScanDistribution = left.getDistributionSpec();

        // shuffle column check
        if (!leftShuffleDistribution.getShuffleColumns().containsAll(leftScanDistribution.getShuffleColumns())) {
            return;
        }

        // right table shuffle columns
        List<Integer> rightBucketShuffleColumns = Lists.newArrayList();

        for (int leftScanColumn : leftScanDistribution.getShuffleColumns()) {
            int index = leftShuffleDistribution.getShuffleColumns().indexOf(leftScanColumn);
            rightBucketShuffleColumns.add(rightShuffleDistribution.getShuffleColumns().get(index));
        }

        // left table local columns
        List<Integer> leftLocalColumns = Lists.newArrayList(leftScanDistribution.getShuffleColumns());

        PhysicalPropertySet rightBucketShuffleProperty = createPropertySetByDistribution(
                DistributionSpec.createHashDistributionSpec(new HashDistributionDesc(rightBucketShuffleColumns,
                        HashDistributionDesc.SourceType.SHUFFLE_JOIN)));
        PhysicalPropertySet leftLocalProperty =
                createPropertySetByDistribution(createLocalByByHashColumns(leftLocalColumns));

        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                Lists.newArrayList(leftLocalProperty, rightBucketShuffleProperty)));
    }

    private Optional<LogicalOlapScanOperator> findLogicalOlapScanOperator(HashDistributionSpec distributionSpec) {
        /*
         * All shuffle columns must come from one table
         * */
        List<ColumnRefOperator> shuffleColumns =
                distributionSpec.getShuffleColumns().stream().map(d -> context.getColumnRefFactory().getColumnRef(d))
                        .collect(Collectors.toList());

        for (LogicalOlapScanOperator scanOperator : taskContext.getAllScanOperators()) {
            if (scanOperator.getOutputColumns().containsAll(shuffleColumns)) {
                return Optional.of(scanOperator);
            }
        }
        return Optional.empty();
    }

    private HashDistributionSpec createLocalByByHashColumns(List<Integer> hashColumns) {
        HashDistributionDesc hashDesc = new HashDistributionDesc(hashColumns, HashDistributionDesc.SourceType.LOCAL);
        return DistributionSpec.createHashDistributionSpec(hashDesc);
    }

    private Optional<HashDistributionDesc> getRequiredLocalDesc() {
        if (!requirements.getDistributionProperty().isShuffle()) {
            return Optional.empty();
        }

        HashDistributionDesc requireDistributionDesc =
                ((HashDistributionSpec) requirements.getDistributionProperty().getSpec()).getHashDistributionDesc();
        if (!HashDistributionDesc.SourceType.LOCAL.equals(requireDistributionDesc.getSourceType())) {
            return Optional.empty();
        }

        return Optional.of(requireDistributionDesc);
    }

    private Void visitJoinRequirements(PhysicalHashJoinOperator node, ExpressionContext context) {
        Optional<HashDistributionDesc> required = getRequiredLocalDesc();

        if (!required.isPresent()) {
            return visitOperator(node, context);
        }

        HashDistributionDesc requireDistributionDesc = required.get();
        ColumnRefSet requiredLocalColumns = new ColumnRefSet();
        requireDistributionDesc.getColumns().forEach(requiredLocalColumns::union);

        ColumnRefSet leftChildColumns = context.getChildOutputColumns(0);
        ColumnRefSet rightChildColumns = context.getChildOutputColumns(1);

        boolean requiredLocalColumnsFromLeft = leftChildColumns.contains(requiredLocalColumns);
        boolean requiredLocalColumnsFromRight = rightChildColumns.contains(requiredLocalColumns);

        // Not support local shuffle column appear on both sides at the same time
        if (requiredLocalColumnsFromLeft == requiredLocalColumnsFromRight) {
            outputInputProps.clear();
            return visitOperator(node, context);
        }

        List<Pair<PhysicalPropertySet, List<PhysicalPropertySet>>> result = Lists.newArrayList();
        if (requiredLocalColumnsFromLeft) {
            for (Pair<PhysicalPropertySet, List<PhysicalPropertySet>> outputInputProp : outputInputProps) {
                PhysicalPropertySet left = outputInputProp.second.get(0);
                PhysicalPropertySet right = outputInputProp.second.get(1);

                if (left.getDistributionProperty().isAny()) {
                    // Broadcast
                    result.add(
                            new Pair<>(distributeRequirements(), Lists.newArrayList(distributeRequirements(), right)));
                } else if (left.getDistributionProperty().isShuffle()) {
                    HashDistributionDesc desc =
                            ((HashDistributionSpec) left.getDistributionProperty().getSpec()).getHashDistributionDesc();

                    if (desc.getSourceType() == HashDistributionDesc.SourceType.LOCAL
                            && requireDistributionDesc.getColumns().containsAll(desc.getColumns())) {
                        // BucketShuffle or Colocate
                        // required local columns is sub-set
                        result.add(new Pair<>(distributeRequirements(), outputInputProp.second));
                    }
                }
            }
        } else {
            for (Pair<PhysicalPropertySet, List<PhysicalPropertySet>> outputInputProp : outputInputProps) {
                PhysicalPropertySet right = outputInputProp.second.get(1);

                if (right.getDistributionProperty().isShuffle()) {
                    HashDistributionDesc desc =
                            ((HashDistributionSpec) right.getDistributionProperty().getSpec())
                                    .getHashDistributionDesc();

                    if (desc.getSourceType() == HashDistributionDesc.SourceType.LOCAL
                            && requireDistributionDesc.getColumns().containsAll(desc.getColumns())) {
                        // only Colocate
                        // required local columns is sub-set
                        result.add(new Pair<>(distributeRequirements(), outputInputProp.second));
                    }
                }
            }
        }

        outputInputProps = result;
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalProject(PhysicalProjectOperator node, ExpressionContext context) {
        // Pass through the requirements to the child
        outputInputProps.add(new Pair<>(distributeRequirements(), Lists.newArrayList(distributeRequirements())));
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalHashAggregate(PhysicalHashAggregateOperator node, ExpressionContext context) {
        // If scan tablet sum leas than 1, do one phase local aggregate is enough
        if (ConnectContext.get().getSessionVariable().getNewPlannerAggStage() == 0
                && context.getRootProperty().isExecuteInOneInstance()
                && node.getType().isGlobal() && !node.isSplit()) {
            outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
            return visitAggregateRequirements(node, context);
        }

        LogicalOperator child = (LogicalOperator) context.getChildOperator(0);
        // If child has limit, we need to gather data to one instance
        if (child.hasLimit() && (node.getType().isGlobal() && !node.isSplit())) {
            outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                    Lists.newArrayList(createLimitGatherProperty(child.getLimit()))));
            return visitAggregateRequirements(node, context);
        }

        if (!node.getType().isLocal()) {
            List<Integer> columns = node.getPartitionByColumns().stream().map(ColumnRefOperator::getId).collect(
                    Collectors.toList());

            // None grouping columns
            if (columns.isEmpty()) {
                DistributionProperty distributionProperty =
                        new DistributionProperty(DistributionSpec.createGatherDistributionSpec());

                outputInputProps.add(new Pair<>(new PhysicalPropertySet(distributionProperty),
                        Lists.newArrayList(new PhysicalPropertySet(distributionProperty))));
                return visitAggregateRequirements(node, context);
            }

            // shuffle aggregation
            DistributionSpec distributionSpec = DistributionSpec.createHashDistributionSpec(
                    new HashDistributionDesc(columns, HashDistributionDesc.SourceType.SHUFFLE_AGG));
            DistributionProperty distributionProperty = new DistributionProperty(distributionSpec);
            outputInputProps.add(new Pair<>(new PhysicalPropertySet(distributionProperty),
                    Lists.newArrayList(new PhysicalPropertySet(distributionProperty))));

            // local aggregation
            DistributionSpec localSpec = DistributionSpec.createHashDistributionSpec(
                    new HashDistributionDesc(columns, HashDistributionDesc.SourceType.LOCAL));
            DistributionProperty localProperty = new DistributionProperty(localSpec);
            outputInputProps.add(new Pair<>(new PhysicalPropertySet(localProperty),
                    Lists.newArrayList(new PhysicalPropertySet(localProperty))));

            return visitAggregateRequirements(node, context);
        }

        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
        return visitAggregateRequirements(node, context);
    }

    private Void visitAggregateRequirements(PhysicalHashAggregateOperator node, ExpressionContext context) {
        Optional<HashDistributionDesc> required = getRequiredLocalDesc();

        if (!required.isPresent()) {
            return visitOperator(node, context);
        }

        HashDistributionDesc requireDistributionDesc = required.get();

        ColumnRefSet requiredLocalColumns = new ColumnRefSet();
        requireDistributionDesc.getColumns().forEach(requiredLocalColumns::union);
        List<Pair<PhysicalPropertySet, List<PhysicalPropertySet>>> result = Lists.newArrayList();

        for (Pair<PhysicalPropertySet, List<PhysicalPropertySet>> outputInputProp : outputInputProps) {
            PhysicalPropertySet input = outputInputProp.second.get(0);

            if (input.getDistributionProperty().isAny()) {
                result.add(new Pair<>(distributeRequirements(), Lists.newArrayList(distributeRequirements())));
            } else if (input.getDistributionProperty().isShuffle()) {
                HashDistributionDesc outputDesc =
                        ((HashDistributionSpec) outputInputProp.first.getDistributionProperty().getSpec())
                                .getHashDistributionDesc();

                HashDistributionDesc inputDesc =
                        ((HashDistributionSpec) input.getDistributionProperty().getSpec()).getHashDistributionDesc();

                if (outputDesc.getSourceType() == HashDistributionDesc.SourceType.LOCAL
                        && inputDesc.getSourceType() == HashDistributionDesc.SourceType.LOCAL
                        && requireDistributionDesc.getColumns().containsAll(inputDesc.getColumns())) {
                    // BucketShuffle or Colocate
                    // required local columns is sub-set
                    result.add(new Pair<>(distributeRequirements(), outputInputProp.second));
                }
            }
        }

        outputInputProps = result;
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalOlapScan(PhysicalOlapScanOperator node, ExpressionContext context) {
        HashDistributionSpec hashDistributionSpec = node.getDistributionSpec();

        ColocateTableIndex colocateIndex = Catalog.getCurrentColocateIndex();
        if (node.getSelectedPartitionId().size() > 1 && !colocateIndex.isColocateTable(node.getTable().getId())) {
            outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList()));
        } else {
            outputInputProps
                    .add(new Pair<>(createPropertySetByDistribution(hashDistributionSpec), Lists.newArrayList()));
        }

        Optional<HashDistributionDesc> required = getRequiredLocalDesc();

        if (!required.isPresent()) {
            return visitOperator(node, context);
        }

        outputInputProps.clear();
        HashDistributionDesc requireDistributionDesc = required.get();
        if (requireDistributionDesc.getColumns().containsAll(hashDistributionSpec.getShuffleColumns())) {
            outputInputProps.add(new Pair<>(distributeRequirements(), Lists.newArrayList()));
        }

        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalTopN(PhysicalTopNOperator topN, ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(topN, context);
        }

        PhysicalPropertySet outputProperty;
        if (topN.getSortPhase().isFinal()) {
            if (topN.isSplit()) {
                DistributionSpec distributionSpec = DistributionSpec.createGatherDistributionSpec();
                DistributionProperty distributionProperty = new DistributionProperty(distributionSpec);
                SortProperty sortProperty = new SortProperty(topN.getOrderSpec());
                outputProperty = new PhysicalPropertySet(distributionProperty, sortProperty);
            } else {
                outputProperty = new PhysicalPropertySet(new SortProperty(topN.getOrderSpec()));
            }
        } else {
            outputProperty = new PhysicalPropertySet();
        }

        LogicalOperator child = (LogicalOperator) context.getChildOperator(0);
        // If child has limit, we need to gather data to one instance
        if (child.hasLimit() && (topN.getSortPhase().isFinal() && !topN.isSplit())) {
            PhysicalPropertySet inputProperty = createLimitGatherProperty(child.getLimit());
            outputInputProps.add(new Pair<>(outputProperty, Lists.newArrayList(inputProperty)));
        } else {
            outputInputProps.add(new Pair<>(outputProperty, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
        }

        return visitOperator(topN, context);
    }

    @Override
    public Void visitPhysicalHiveScan(PhysicalHiveScanOperator node, ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList()));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalSchemaScan(PhysicalSchemaScanOperator node, ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList()));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalMysqlScan(PhysicalMysqlScanOperator node, ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList()));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalEsScan(PhysicalEsScanOperator node, ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList()));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalAssertOneRow(PhysicalAssertOneRowOperator node, ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            return visitOperator(node, context);
        }
        DistributionSpec gather = DistributionSpec.createGatherDistributionSpec();
        DistributionProperty inputProperty = new DistributionProperty(gather);

        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY,
                Lists.newArrayList(new PhysicalPropertySet(inputProperty))));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalAnalytic(PhysicalWindowOperator node, ExpressionContext context) {
        List<Integer> partitionColumnRefSet = new ArrayList<>();
        List<Ordering> orderings = new ArrayList<>();

        node.getPartitionExpressions().forEach(e -> {
            partitionColumnRefSet
                    .addAll(Arrays.stream(e.getUsedColumns().getColumnIds()).boxed().collect(Collectors.toList()));
            orderings.add(new Ordering((ColumnRefOperator) e, true, true));
        });

        node.getOrderByElements().forEach(o -> {
            if (orderings.stream().noneMatch(ordering -> ordering.getColumnRef().equals(o.getColumnRef()))) {
                orderings.add(o);
            }
        });

        SortProperty sortProperty = new SortProperty(new OrderSpec(orderings));

        Optional<HashDistributionDesc> required = getRequiredLocalDesc();
        if (required.isPresent()) {
            if (!partitionColumnRefSet.isEmpty() && required.get().getColumns().containsAll(partitionColumnRefSet)) {
                // local
                DistributionProperty localProperty = new DistributionProperty(DistributionSpec
                        .createHashDistributionSpec(new HashDistributionDesc(partitionColumnRefSet,
                                HashDistributionDesc.SourceType.LOCAL)));
                outputInputProps.add(new Pair<>(new PhysicalPropertySet(localProperty, sortProperty),
                        Lists.newArrayList(new PhysicalPropertySet(localProperty, sortProperty))));
            }

            return visitOperator(node, context);
        }

        if (partitionColumnRefSet.isEmpty()) {
            DistributionProperty distributionProperty =
                    new DistributionProperty(DistributionSpec.createGatherDistributionSpec());
            outputInputProps.add(new Pair<>(new PhysicalPropertySet(distributionProperty, sortProperty),
                    Lists.newArrayList(new PhysicalPropertySet(distributionProperty, sortProperty))));
        } else {
            DistributionProperty distributionProperty = new DistributionProperty(DistributionSpec
                    .createHashDistributionSpec(
                            new HashDistributionDesc(partitionColumnRefSet,
                                    HashDistributionDesc.SourceType.SHUFFLE_AGG)));
            outputInputProps.add(new Pair<>(new PhysicalPropertySet(distributionProperty, sortProperty),
                    Lists.newArrayList(new PhysicalPropertySet(distributionProperty, sortProperty))));

            // local
            DistributionProperty localProperty = new DistributionProperty(DistributionSpec.createHashDistributionSpec(
                    new HashDistributionDesc(partitionColumnRefSet, HashDistributionDesc.SourceType.LOCAL)));
            outputInputProps.add(new Pair<>(new PhysicalPropertySet(localProperty, sortProperty),
                    Lists.newArrayList(new PhysicalPropertySet(localProperty, sortProperty))));
        }

        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalUnion(PhysicalUnionOperator node, ExpressionContext context) {
        processSetOperationChildProperty(context);
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalExcept(PhysicalExceptOperator node, ExpressionContext context) {
        processSetOperationChildProperty(context);
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalIntersect(PhysicalIntersectOperator node, ExpressionContext context) {
        processSetOperationChildProperty(context);
        return visitOperator(node, context);
    }

    private void processSetOperationChildProperty(ExpressionContext context) {
        if (getRequiredLocalDesc().isPresent()) {
            // Set operator can't support local distribute
            return;
        }
        List<PhysicalPropertySet> childProperty = new ArrayList<>();
        for (int i = 0; i < context.arity(); ++i) {
            LogicalOperator child = (LogicalOperator) context.getChildOperator(i);
            // If child has limit, we need to gather data to one instance
            if (child.hasLimit()) {
                childProperty.add(createLimitGatherProperty(child.getLimit()));
            } else {
                childProperty.add(PhysicalPropertySet.EMPTY);
            }
        }

        // Use Any to forbidden enforce some property, will add shuffle in FragmentBuilder
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, childProperty));
    }

    @Override
    public Void visitPhysicalValues(PhysicalValuesOperator node, ExpressionContext context) {
        outputInputProps.add(new Pair<>(distributeRequirements(), Lists.newArrayList()));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalRepeat(PhysicalRepeatOperator node, ExpressionContext context) {
        // Pass through the requirements to the child
        if (getRequiredLocalDesc().isPresent()) {
            outputInputProps.add(new Pair<>(distributeRequirements(), Lists.newArrayList(distributeRequirements())));
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalFilter(PhysicalFilterOperator node, ExpressionContext context) {
        // Pass through the requirements to the child
        if (getRequiredLocalDesc().isPresent()) {
            outputInputProps.add(new Pair<>(distributeRequirements(), Lists.newArrayList(distributeRequirements())));
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
        return visitOperator(node, context);
    }

    @Override
    public Void visitPhysicalTableFunction(PhysicalTableFunctionOperator node, ExpressionContext context) {
        // Pass through the requirements to the child
        if (getRequiredLocalDesc().isPresent()) {
            outputInputProps.add(new Pair<>(distributeRequirements(), Lists.newArrayList(distributeRequirements())));
            return visitOperator(node, context);
        }
        outputInputProps.add(new Pair<>(PhysicalPropertySet.EMPTY, Lists.newArrayList(PhysicalPropertySet.EMPTY)));
        return visitOperator(node, context);
    }

    private PhysicalPropertySet createLimitGatherProperty(long limit) {
        DistributionSpec distributionSpec = DistributionSpec.createGatherDistributionSpec(limit);
        DistributionProperty distributionProperty = new DistributionProperty(distributionSpec);
        return new PhysicalPropertySet(distributionProperty, SortProperty.EMPTY);
    }

    private PhysicalPropertySet createPropertySetByDistribution(DistributionSpec distributionSpec) {
        DistributionProperty distributionProperty = new DistributionProperty(distributionSpec);
        return new PhysicalPropertySet(distributionProperty);
    }
}
