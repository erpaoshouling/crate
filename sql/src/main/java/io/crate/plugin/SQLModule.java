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

package io.crate.plugin;

import io.crate.action.sql.DDLAnalysisDispatcher;
import io.crate.action.sql.SQLAction;
import io.crate.action.sql.TransportSQLAction;
import io.crate.metadata.FulltextAnalyzerResolver;
import org.elasticsearch.action.GenericAction;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.multibindings.MapBinder;


public class SQLModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(TransportSQLAction.class).asEagerSingleton();
        bind(DDLAnalysisDispatcher.class).asEagerSingleton();
        bind(FulltextAnalyzerResolver.class).asEagerSingleton();
        MapBinder<GenericAction, TransportAction> transportActionsBinder = MapBinder.newMapBinder(binder(), GenericAction.class,
                TransportAction.class);

        transportActionsBinder.addBinding(SQLAction.INSTANCE).to(TransportSQLAction.class).asEagerSingleton();

        MapBinder<String, GenericAction> actionsBinder = MapBinder.newMapBinder(binder(), String.class, GenericAction.class);
        actionsBinder.addBinding(SQLAction.NAME).toInstance(SQLAction.INSTANCE);

    }
}