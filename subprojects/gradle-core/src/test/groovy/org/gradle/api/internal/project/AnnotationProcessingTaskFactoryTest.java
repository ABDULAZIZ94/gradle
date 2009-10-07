/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal.project;

import org.gradle.api.*;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.AbstractTask;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.TaskAction;
import org.gradle.integtests.TestFile;
import org.gradle.util.GFileUtils;
import org.gradle.util.HelperUtil;
import static org.gradle.util.Matchers.*;
import org.gradle.util.TemporaryFolder;
import static org.gradle.util.WrapUtil.*;
import static org.hamcrest.Matchers.*;
import org.jmock.Expectations;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.jmock.lib.legacy.ClassImposteriser;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@RunWith(JMock.class)
public class AnnotationProcessingTaskFactoryTest {
    private final JUnit4Mockery context = new JUnit4Mockery() {{
        setImposteriser(ClassImposteriser.INSTANCE);
    }};

    private final ITaskFactory delegate = context.mock(ITaskFactory.class);
    private final Project project = context.mock(Project.class);
    private final Map args = new HashMap();
    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();
    private final TestFile testDir = tmpDir.getDir();
    private final File existingFile = testDir.file("file.txt").touch();
    private final File missingFile = testDir.file("missing.txt");
    private final File existingDir = testDir.file("dir").createDir();
    private final File missingDir = testDir.file("missing-dir");
    private final AnnotationProcessingTaskFactory factory = new AnnotationProcessingTaskFactory(delegate);

    @Test
    public void attachesAnActionToTaskForMethodMarkedWithTaskActionAnnotation() {
        final Runnable action = context.mock(Runnable.class);
        final TestTask task = expectTaskCreated(TestTask.class, action);

        context.checking(new Expectations() {{
            one(action).run();
        }});
        task.execute();
    }

    private <T extends Task> T expectTaskCreated(final Class<T> type, final Object... params) {
        T task = AbstractTask.injectIntoNewInstance(HelperUtil.createRootProject(), "task", new Callable<T>() {
            public T call() throws Exception {
                if (params.length > 0) {
                    return type.cast(type.getConstructors()[0].newInstance(params));
                } else {
                    return type.newInstance();
                }
            }
        });
        return expectTaskCreated(task);
    }

    private <T extends Task> T expectTaskCreated(final T task) {
        context.checking(new Expectations() {{
            one(delegate).createTask(project, args);
            will(returnValue(task));
        }});

        assertThat(factory.createTask(project, args), sameInstance((Object) task));
        return task;
    }

    @Test
    public void doesNothingToTaskWithNoTaskActionAnnotations() {
        TaskInternal task = expectTaskCreated(DefaultTask.class);

        assertThat(task.getActions(), isEmpty());
    }

    @Test
    public void propagatesExceptionThrownByTaskActionMethod() {
        final Runnable action = context.mock(Runnable.class);
        TestTask task = expectTaskCreated(new TestTask(action));

        final RuntimeException failure = new RuntimeException();
        context.checking(new Expectations() {{
            one(action).run();
            will(throwException(failure));
        }});

        try {
            task.getActions().get(0).execute(task);
            fail();
        } catch (RuntimeException e) {
            assertThat(e, sameInstance(failure));
        }
    }

    @Test
    public void canHaveMultipleMethodsWithTaskActionAnnotation() {
        final Runnable action = context.mock(Runnable.class);
        TaskWithMultipleMethods task = expectTaskCreated(TaskWithMultipleMethods.class, action);

        context.checking(new Expectations() {{
            exactly(3).of(action).run();
        }});

        task.execute();
    }

    @Test
    public void cachesClassMetaInfo() {
        TaskWithInputFile task = expectTaskCreated(new TaskWithInputFile(null));
        TaskWithInputFile task2 = expectTaskCreated(new TaskWithInputFile(null));

        assertThat(task.getActions().get(0), sameInstance((Action) task2.getActions().get(0)));
    }
    
    @Test
    public void failsWhenStaticMethodHasTaskActionAnnotation() {
        TaskWithStaticMethod task = new TaskWithStaticMethod();
        assertTaskCreationFails(task,
                "Cannot use @TaskAction annotation on static method TaskWithStaticMethod.doStuff().");
    }

    @Test
    public void failsWhenMethodWithParametersHasTaskActionAnnotation() {
        TaskWithParamMethod task = new TaskWithParamMethod();
        assertTaskCreationFails(task,
                "Cannot use @TaskAction annotation on method TaskWithParamMethod.doStuff() as this method takes parameters.");
    }

    private void assertTaskCreationFails(Task task, String message) {
        try {
            expectTaskCreated(task);
            fail();
        } catch (GradleException e) {
            assertThat(e.getMessage(), equalTo(message));
        }
    }

    @Test
    public void taskActionWorksForInheritedMethods() {
        final Runnable action = context.mock(Runnable.class);
        TaskWithInheritedMethod task = expectTaskCreated(TaskWithInheritedMethod.class, action);

        context.checking(new Expectations() {{
            one(action).run();
        }});
        task.execute();
    }

    @Test
    public void taskActionWorksForProtectedMethods() {
        final Runnable action = context.mock(Runnable.class);
        TaskWithProtectedMethod task = expectTaskCreated(TaskWithProtectedMethod.class, action);

        context.checking(new Expectations() {{
            one(action).run();
        }});
        task.execute();
    }

    @Test
    public void validationActionSucceedsWhenSpecifiedInputFileExists() {
        TaskWithInputFile task = expectTaskCreated(TaskWithInputFile.class, existingFile);
        task.execute();
    }

    @Test
    public void validationActionFailsWhenInputFileNotSpecified() {
        TaskWithInputFile task = expectTaskCreated(TaskWithInputFile.class, new Object[]{null});
        assertValidationFails(task, "No value has been specified for property 'inputFile'.");
    }

    @Test
    public void validationActionFailsWhenInputFileDoesNotExist() {
        TaskWithInputFile task = expectTaskCreated(TaskWithInputFile.class, missingFile);
        assertValidationFails(task, String.format("File '%s' specified for property 'inputFile' does not exist.",
                task.inputFile));
    }

    @Test
    public void validationActionFailsWhenInputFileIsADirectory() {
        TaskWithInputFile task = expectTaskCreated(TaskWithInputFile.class, existingDir);
        assertValidationFails(task, String.format("File '%s' specified for property 'inputFile' is not a file.",
                task.inputFile));
    }

    @Test
    public void registersSpecifiedInputFile() {
        TaskWithInputFile task = expectTaskCreated(TaskWithInputFile.class, existingFile);
        assertThat(task.getInputs().getInputFiles().getFiles(), equalTo(toSet(existingFile)));
    }

    @Test
    public void doesNotRegistersInputFileWhenNoneSpecified() {
        TaskWithInputFile task = expectTaskCreated(new TaskWithInputFile(null));
        assertThat(task.getInputs().getInputFiles().getFiles(), isEmpty());
    }
    
    @Test
    public void validationActionSucceedsWhenSpecifiedOutputFileIsAFile() {
        TaskWithOutputFile task = expectTaskCreated(TaskWithOutputFile.class, existingFile);
        task.execute();
    }

    @Test
    public void validationActionSucceedsWhenSpecifiedOutputFileIsNotAFile() {
        TaskWithOutputFile task = expectTaskCreated(TaskWithOutputFile.class, new File(testDir, "subdir/output.txt"));

        task.execute();

        assertTrue(new File(testDir, "subdir").isDirectory());
    }

    @Test
    public void validationActionFailsWhenOutputFileNotSpecified() {
        TaskWithOutputFile task = expectTaskCreated(TaskWithOutputFile.class, new Object[]{null});
        assertValidationFails(task, "No value has been specified for property 'outputFile'.");
    }

    @Test
    public void validationActionFailsWhenSpecifiedOutputFileIsADirectory() {
        TaskWithOutputFile task = expectTaskCreated(TaskWithOutputFile.class, existingDir);
        assertValidationFails(task, String.format(
                "Cannot write to file '%s' specified for property 'outputFile' as it is a directory.",
                task.outputFile));
    }

    @Test
    public void validationActionFailsWhenSpecifiedOutputFileParentIsAFile() {
        TaskWithOutputFile task = expectTaskCreated(TaskWithOutputFile.class, new File(testDir, "subdir/output.txt"));
        GFileUtils.touch(task.outputFile.getParentFile());

        assertValidationFails(task, String.format(
                "Cannot create parent directory '%s' of file specified for property 'outputFile'.",
                task.outputFile.getParentFile()));
    }

    @Test
    public void registersSpecifiedOutputFile() {
        TaskWithOutputFile task = expectTaskCreated(TaskWithOutputFile.class, existingFile);
        assertThat(task.getOutputs().getOutputFiles().getFiles(), equalTo(toSet(existingFile)));
    }

    @Test
    public void doesNotRegisterOutputFileWhenNoneSpecified() {
        TaskWithOutputFile task = expectTaskCreated(new TaskWithOutputFile(null));
        assertThat(task.getOutputs().getOutputFiles().getFiles(), isEmpty());
    }

    @Test
    public void validationActionSucceedsWhenInputFilesSpecified() {
        TaskWithInputFiles task = expectTaskCreated(TaskWithInputFiles.class, toList(testDir));
        task.execute();
    }

    @Test
    public void validationActionFailsWhenInputFilesNotSpecified() {
        TaskWithInputFiles task = expectTaskCreated(TaskWithInputFiles.class, new Object[]{null});
        assertValidationFails(task, "No value has been specified for property 'input'.");
    }

    @Test
    public void registersSpecifiedInputFiles() {
        TaskWithInputFiles task = expectTaskCreated(TaskWithInputFiles.class, toList(testDir, missingFile));
        assertThat(task.getInputs().getInputFiles().getFiles(), equalTo(toSet(testDir, missingFile)));
    }

    @Test
    public void doesNotRegisterInputFilesWhenNoneSpecified() {
        TaskWithInputFiles task = expectTaskCreated(new TaskWithInputFiles(null));
        assertThat(task.getInputs().getInputFiles().getFiles(), isEmpty());
    }

    @Test
    public void skipsTaskWhenInputFileCollectionIsEmpty() {
        final FileCollection inputFiles = context.mock(FileCollection.class);
        context.checking(new Expectations() {{
            one(inputFiles).stopExecutionIfEmpty();
            will(throwException(new StopExecutionException()));
        }});

        TaskWithInputFiles task = expectTaskCreated(BrokenTaskWithInputFiles.class, inputFiles);

        task.execute();
    }

    @Test
    public void validationActionSucceedsWhenSpecifiedOutputDirectoryDoesNotExist() {
        TaskWithOutputDir task = expectTaskCreated(TaskWithOutputDir.class, missingDir);
        task.execute();

        assertTrue(task.outputDir.isDirectory());
    }

    @Test
    public void validationActionSucceedsWhenSpecifiedOutputDirectoryIsDirectory() {
        TaskWithOutputDir task = expectTaskCreated(TaskWithOutputDir.class, existingDir);
        task.execute();
    }

    @Test
    public void validationActionFailsWhenOutputDirectoryNotSpecified() {
        TaskWithOutputDir task = expectTaskCreated(TaskWithOutputDir.class, new Object[]{null});
        assertValidationFails(task, "No value has been specified for property 'outputDir'.");
    }

    @Test
    public void validationActionFailsWhenOutputDirectoryIsAFile() {
        TaskWithOutputDir task = expectTaskCreated(TaskWithOutputDir.class, existingFile);
        assertValidationFails(task, String.format("Cannot create directory '%s' specified for property 'outputDir'.",
                task.outputDir));
    }

    @Test
    public void validationActionFailsWhenParentOfOutputDirectoryIsAFile() {
        TaskWithOutputDir task = expectTaskCreated(TaskWithOutputDir.class, new File(testDir, "subdir/output"));
        GFileUtils.touch(task.outputDir.getParentFile());

        assertValidationFails(task, String.format("Cannot create directory '%s' specified for property 'outputDir'.",
                task.outputDir));
    }

    @Test
    public void registersSpecifiedOutputDirectory() {
        TaskWithOutputDir task = expectTaskCreated(TaskWithOutputDir.class, missingDir);
        assertThat(task.getOutputs().getOutputFiles().getFiles(), equalTo(toSet(missingDir)));
    }

    @Test
    public void doesNotRegisterOutputDirectoryWhenNoneSpecified() {
        TaskWithOutputDir task = expectTaskCreated(new TaskWithOutputDir(null));
        assertThat(task.getOutputs().getOutputFiles().getFiles(), isEmpty());
    }

    @Test
    public void validationActionSucceedsWhenSpecifiedInputDirectoryIsDirectory() {
        TaskWithInputDir task = expectTaskCreated(TaskWithInputDir.class, existingDir);
        task.execute();
    }

    @Test
    public void validationActionFailsWhenInputDirectoryNotSpecified() {
        TaskWithInputDir task = expectTaskCreated(TaskWithInputDir.class, new Object[]{null});
        assertValidationFails(task, "No value has been specified for property 'inputDir'.");
    }
    
    @Test
    public void validationActionFailsWhenInputDirectoryDoesNotExist() {
        TaskWithInputDir task = expectTaskCreated(TaskWithInputDir.class, missingDir);
        assertValidationFails(task, String.format("Directory '%s' specified for property 'inputDir' does not exist.",
                task.inputDir));
    }

    @Test
    public void validationActionFailsWhenInputDirectoryIsAFile() {
        TaskWithInputDir task = expectTaskCreated(TaskWithInputDir.class, existingFile);
        GFileUtils.touch(task.inputDir);

        assertValidationFails(task, String.format(
                "Directory '%s' specified for property 'inputDir' is not a directory.", task.inputDir));
    }

    @Test
    public void registersSpecifiedInputDirectory() {
        TaskWithInputDir task = expectTaskCreated(TaskWithInputDir.class, existingDir);
        assertThat(task.getInputs().getInputFiles().getFiles(), equalTo(toSet(existingDir)));
    }

    @Test
    public void doesNotRegisterInputDirectoryWhenNoneSpecified() {
        TaskWithInputDir task = expectTaskCreated(new TaskWithInputDir(null));
        assertThat(task.getInputs().getInputFiles().getFiles(), isEmpty());
    }

    @Test
    public void skipsTaskWhenInputDirectoryIsEmptyAndSkipWhenEmpty() {
        TaskWithInputDir task = expectTaskCreated(BrokenTaskWithInputDir.class, existingDir);
        task.execute();
    }

    @Test
    public void skipsTaskWhenInputDirectoryIsDoesNotExistAndSkipWhenEmpty() {
        TaskWithInputDir task = expectTaskCreated(BrokenTaskWithInputDir.class, missingDir);
        task.execute();
    }
    
    @Test
    public void validationActionSucceedsWhenPropertyMarkedWithOptionalAnnotationNotSpecified() {
        TaskWithOptionalInputFile task = expectTaskCreated(TaskWithOptionalInputFile.class);
        task.execute();
    }

    @Test
    public void canAttachAnnotationToGroovyProperty() {
        InputFileTask task = expectTaskCreated(InputFileTask.class);
        assertValidationFails(task, "No value has been specified for property 'srcFile'.");
    }
    
    private void assertValidationFails(TaskInternal task, String expectedErrorMessage) {
        try {
            task.execute();
            fail();
        } catch (GradleException e) {
            assertThat(e.getCause(), instanceOf(InvalidUserDataException.class));
            assertThat(e.getCause().getMessage(), equalTo(expectedErrorMessage));
        }
    }

    public static class TestTask extends DefaultTask {
        final Runnable action;

        public TestTask(Runnable action) {
            this.action = action;
        }

        @TaskAction
        public void doStuff() {
            action.run();
        }
    }

    public static class TaskWithInheritedMethod extends TestTask {
        public TaskWithInheritedMethod(Runnable action) {
            super(action);
        }
    }

    public static class TaskWithProtectedMethod extends DefaultTask {
        private final Runnable action;

        public TaskWithProtectedMethod(Runnable action) {
            this.action = action;
        }

        @TaskAction
        protected void doStuff() {
            action.run();
        }
    }

    public static class TaskWithStaticMethod extends DefaultTask {
        @TaskAction
        public static void doStuff() {
        }
    }

    public static class TaskWithMultipleMethods extends TestTask {
        public TaskWithMultipleMethods(Runnable action) {
            super(action);
        }

        @TaskAction
        public void aMethod() {
            action.run();
        }

        @TaskAction
        public void anotherMethod() {
            action.run();
        }
    }

    public static class TaskWithParamMethod extends DefaultTask {
        @TaskAction
        public void doStuff(int value) {
        }
    }

    public static class TaskWithInputFile extends DefaultTask {
        File inputFile;

        public TaskWithInputFile(File inputFile) {
            this.inputFile = inputFile;
        }

        @InputFile
        public File getInputFile() {
            return inputFile;
        }
    }

    public static class TaskWithInputDir extends DefaultTask {
        File inputDir;

        public TaskWithInputDir(File inputDir) {
            this.inputDir = inputDir;
        }

        @InputDirectory
        public File getInputDir() {
            return inputDir;
        }
    }

    public static class BrokenTaskWithInputDir extends TaskWithInputDir {
        public BrokenTaskWithInputDir(File inputDir) {
            super(inputDir);
        }

        @Override @InputDirectory @SkipWhenEmpty
        public File getInputDir() {
            return super.getInputDir();
        }

        @TaskAction
        public void doStuff() {
            fail();
        }

    }

    public static class TaskWithOutputFile extends DefaultTask {
        File outputFile;

        public TaskWithOutputFile(File outputFile) {
            this.outputFile = outputFile;
        }

        @OutputFile
        public File getOutputFile() {
            return outputFile;
        }
    }

    public static class TaskWithOutputDir extends DefaultTask {
        File outputDir;

        public TaskWithOutputDir(File outputDir) {
            this.outputDir = outputDir;
        }

        @OutputDirectory
        public File getOutputDir() {
            return outputDir;
        }
    }

    public static class TaskWithInputFiles extends DefaultTask {
        Iterable<? extends File> input;

        public TaskWithInputFiles(Iterable<? extends File> input) {
            this.input = input;
        }

        @InputFiles @SkipWhenEmpty
        public Iterable<? extends File> getInput() {
            return input;
        }
    }

    public static class BrokenTaskWithInputFiles extends TaskWithInputFiles {
        public BrokenTaskWithInputFiles(Iterable<? extends File> input) {
            super(input);
        }

        @TaskAction
        public void doStuff() {
            fail();
        }
    }

    public static class TaskWithOptionalInputFile extends DefaultTask {
        @InputFile @Optional
        public File getInputFile() {
            return null;
        }
    }
}
