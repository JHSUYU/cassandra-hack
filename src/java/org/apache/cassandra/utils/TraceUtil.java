/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;

public class TraceUtil
{
    public static boolean debug = false;

    public static final String DRY_RUN_KEY= "is_dry_run";

    public static ContextKey<Boolean> IS_DRY_RUN = ContextKey.named(DRY_RUN_KEY);

    public static boolean isDryRun() {
        if(debug){
            return false;
        }else{
            return Baggage.current().getEntryValue(DRY_RUN_KEY) != null && Boolean.parseBoolean(Baggage.current().getEntryValue(DRY_RUN_KEY));
        }
        //return false;
    }

    public static Baggage createDryRunBaggage() {
        Baggage dryRunBaggage = Baggage.current().toBuilder().put(DRY_RUN_KEY, "true").build();
        dryRunBaggage.makeCurrent();
        Context.current().with(dryRunBaggage).makeCurrent();
        return dryRunBaggage;
    }

}
