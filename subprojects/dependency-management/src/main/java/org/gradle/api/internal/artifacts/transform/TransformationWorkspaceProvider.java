/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.Try;
import org.gradle.internal.execution.history.ExecutionHistoryStore;

import java.io.File;

public interface TransformationWorkspaceProvider {
    /**
     * Provides a workspace for executing the transformation.
     */
    Try<ImmutableList<File>> withWorkspace(TransformationIdentity identity, TransformationWorkspaceAction workspaceAction);

    /**
     * The execution history store for transformations using the provided workspaces.
     */
    ExecutionHistoryStore getExecutionHistoryStore();

    @FunctionalInterface
    interface TransformationWorkspaceAction {
        Try<ImmutableList<File>> useWorkspace(String transformationIdentity, TransformationWorkspace workspace);
    }

    interface TransformationWorkspace {
        File getOutputDirectory();
        File getResultsFile();
    }
}
