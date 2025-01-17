/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.ExtensionUtils.hapiTestMethodOf;

import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SpecNamingExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(@NonNull final ExtensionContext extensionContext) {
        hapiTestMethodOf(extensionContext)
                .ifPresent(method -> HapiSpec.SPEC_NAME.set(
                        extensionContext.getRequiredTestClass().getSimpleName() + "." + method.getName()));
    }
}
