/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.projectors;

import com.google.common.collect.ImmutableList;
import io.crate.analyze.EvaluatingNormalizer;
import io.crate.operation.ImplementationSymbolVisitor;
import io.crate.operation.Input;
import io.crate.operation.collect.CollectExpression;
import io.crate.planner.projection.*;
import io.crate.planner.symbol.Aggregation;
import io.crate.planner.symbol.StringLiteral;
import io.crate.planner.symbol.StringValueSymbolVisitor;
import io.crate.planner.symbol.Symbol;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Provider;

import java.util.ArrayList;
import java.util.List;

public class ProjectionToProjectorVisitor extends ProjectionVisitor<Void, Projector> {

    private final ImplementationSymbolVisitor symbolVisitor;
    private final EvaluatingNormalizer normalizer;
    private final Provider<Client> clientProvider;

    public Projector process(Projection projection) {
        return process(projection, null);
    }

    public ProjectionToProjectorVisitor(Provider<Client> clientProvider, ImplementationSymbolVisitor symbolVisitor,
            EvaluatingNormalizer normalizer) {
        this.clientProvider = clientProvider;
        this.symbolVisitor = symbolVisitor;
        this.normalizer = normalizer;
    }

    public ProjectionToProjectorVisitor(Provider<Client> clientProvider, ImplementationSymbolVisitor symbolVisitor) {
        this(clientProvider, symbolVisitor, new EvaluatingNormalizer(
                symbolVisitor.functions(), symbolVisitor.rowGranularity(), symbolVisitor.referenceResolver()));
    }

    @Override
    public Projector visitColumnProjection(ColumnProjection projection, Void context) {
        return super.visitColumnProjection(projection, context);
    }

    @Override
    public Projector visitTopNProjection(TopNProjection projection, Void context) {
        Projector projector;
        List<Input<?>> inputs = new ArrayList<>();
        List<CollectExpression<?>> collectExpressions = new ArrayList<>();

        ImplementationSymbolVisitor.Context ctx = symbolVisitor.process(projection.outputs());
        inputs.addAll(ctx.topLevelInputs());
        collectExpressions.addAll(ctx.collectExpressions());

        if (projection.isOrdered()) {
            int numOutputs = inputs.size();
            ImplementationSymbolVisitor.Context orderByCtx = symbolVisitor.process(projection.orderBy());

            // append orderby inputs to row, needed for sorting on them
            inputs.addAll(orderByCtx.topLevelInputs());
            collectExpressions.addAll(orderByCtx.collectExpressions());

            int[] orderByIndices = new int[inputs.size() - numOutputs];
            int idx = 0;
            for (int i = numOutputs; i < inputs.size(); i++) {
                orderByIndices[idx++] = i;
            }

            projector = new SortingTopNProjector(
                    inputs.toArray(new Input<?>[inputs.size()]),
                    collectExpressions.toArray(new CollectExpression[collectExpressions.size()]),
                    numOutputs,
                    orderByIndices,
                    projection.reverseFlags(),
                    projection.limit(),
                    projection.offset());
        } else {
            projector = new SimpleTopNProjector(
                    inputs.toArray(new Input<?>[inputs.size()]),
                    collectExpressions.toArray(new CollectExpression[collectExpressions.size()]),
                    projection.limit(),
                    projection.offset());
        }
        return projector;
    }

    @Override
    public Projector visitGroupProjection(GroupProjection projection, Void context) {
        ImplementationSymbolVisitor.Context symbolContext = symbolVisitor.process(projection.keys());
        List<Input<?>> keyInputs = symbolContext.topLevelInputs();

        for (Aggregation aggregation : projection.values()) {
            symbolVisitor.process(aggregation, symbolContext);
        }
        Projector groupProjector = new GroupingProjector(
                keyInputs,
                ImmutableList.copyOf(symbolContext.collectExpressions()),
                symbolContext.aggregations()
        );
        return groupProjector;
    }

    @Override
    public Projector visitAggregationProjection(AggregationProjection projection, Void context) {
        ImplementationSymbolVisitor.Context symbolContext = new ImplementationSymbolVisitor.Context();
        for (Aggregation aggregation : projection.aggregations()) {
            symbolVisitor.process(aggregation, symbolContext);
        }
        Projector aggregationProjector = new AggregationProjector(
                symbolContext.collectExpressions(),
                symbolContext.aggregations());
        return aggregationProjector;
    }

    @Override
    public Projector visitWriterProjection(WriterProjection projection, Void context) {
        ImplementationSymbolVisitor.Context symbolContext = new ImplementationSymbolVisitor.Context();

        List<Input<?>> inputs = null;
        if (!projection.inputs().isEmpty()) {
            inputs = new ArrayList<>(projection.inputs().size());
            for (Symbol symbol : projection.inputs()) {
                inputs.add(symbolVisitor.process(symbol, symbolContext));
            }
        }

        projection = projection.normalize(normalizer);
        String uri = StringValueSymbolVisitor.INSTANCE.process(projection.uri());
        if (projection.isDirectoryUri()) {
            StringBuilder sb = new StringBuilder(uri);
            Symbol resolvedFileName = normalizer.normalize(WriterProjection.DIRECTORY_TO_FILENAME);
            assert resolvedFileName instanceof StringLiteral;
            String fileName = StringValueSymbolVisitor.INSTANCE.process(resolvedFileName);
            if (!uri.endsWith("/")) {
                sb.append("/");
            }
            sb.append(fileName);
            if (projection.settings().get("compression", "").equalsIgnoreCase("gzip")) {
                sb.append(".gz");
            }
            uri = sb.toString();
        }
        return new WriterProjector(
                uri,
                projection.settings(),
                inputs,
                symbolContext.collectExpressions()
        );
    }

    public Projector visitIndexWriterProjection(IndexWriterProjection projection, Void context) {
        ImplementationSymbolVisitor.Context symbolContext = new ImplementationSymbolVisitor.Context();
        List<Input<?>> idInputs = new ArrayList<>(projection.ids().size());
        for (Symbol idSymbol : projection.ids()) {
            idInputs.add(symbolVisitor.process(idSymbol, symbolContext));
        }
        List<Input<?>> partitionedByInputs = new ArrayList<>(projection.partitionedBySymbols().size());
        for (Symbol partitionedBySymbol : projection.partitionedBySymbols()) {
            partitionedByInputs.add(symbolVisitor.process(partitionedBySymbol, symbolContext));
        }
        Input<?> sourceInput = symbolVisitor.process(projection.rawSource(), symbolContext);
        Input<?> clusteredBy = symbolVisitor.process(projection.clusteredBy(), symbolContext);
        return new IndexWriterProjector(
                clientProvider.get(),
                projection.tableName(),
                projection.primaryKeys(),
                idInputs,
                partitionedByInputs,
                clusteredBy,
                sourceInput,
                symbolContext.collectExpressions().toArray(new CollectExpression[symbolContext.collectExpressions().size()]),
                projection.bulkActions(),
                projection.concurrency(),
                projection.includes(),
                projection.excludes()
        );
    }
}
