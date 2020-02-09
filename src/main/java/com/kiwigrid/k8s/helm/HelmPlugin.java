package com.kiwigrid.k8s.helm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Collections;

import com.kiwigrid.k8s.helm.tasks.HelmDeployTask;
import com.kiwigrid.k8s.helm.tasks.HelmBuildTask;
import com.kiwigrid.k8s.helm.tasks.HelmInitTask;
import com.kiwigrid.k8s.helm.tasks.HelmTestTask;
import com.kiwigrid.k8s.helm.tasks.RepoSyncTask;
import de.undercouch.gradle.tasks.download.Download;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;
import org.gradle.process.internal.ExecException;
import org.gradle.util.VersionNumber;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

public class HelmPlugin implements Plugin<Project> {

	public static final Yaml YAML = new Yaml();
	public static final String EXTENSION_NAME = "helm";

	public static final String LINT_WITH_VALUES_VERSION = "2.9.0-rc3";
	public static final String TEMPLATE_WITH_OUTPUT_VERSION = "2.8.0";
	public static final String REPO_AUTHENTICATION_VERSION = "2.9.0-rc3";

	public static String getHelmExecutable(HelmSpec helmSpec) {
		return OperatingSystem.current()
				.getExecutableName(helmSpec.getHelmExecutableDirectory().getAbsolutePath() + "/helm");
	}

	public static boolean lintWithValuesSupported(String version) {
		return versionIsGreaterThanOrEquals(version, LINT_WITH_VALUES_VERSION);
	}

	public static boolean templateWithOutputSupported(String version) {
		return versionIsGreaterThanOrEquals(version, TEMPLATE_WITH_OUTPUT_VERSION);
	}

	public static boolean authenticatedReposSupported(String version) {
		return versionIsGreaterThanOrEquals(version, REPO_AUTHENTICATION_VERSION);
	}

	private static boolean versionIsGreaterThanOrEquals(String availableVersionString, String featureVersion) {
		// we assume canary has it all
		if (isCanaryVersion(availableVersionString)) {
			return true;
		}
		VersionNumber availableVersion = VersionNumber.parse(availableVersionString);
		VersionNumber requiredVersion = VersionNumber.parse(featureVersion);
		return availableVersion.compareTo(requiredVersion) >= 0;
	}

	static boolean isCanaryVersion(String version) {
		return "canary".equals(version);
	}

	public void apply(Project project) {
		// apply required plugins
		project.getPluginManager().apply("base");
		project.getPluginManager().apply("de.undercouch.download");

		// register DSL
		HelmPluginExtension extension = project
				.getExtensions()
				.create(
						EXTENSION_NAME,
						HelmPluginExtension.class,
						project);

		// create required tasks
		TaskContainer projectTasks = project.getTasks();
		TaskProvider<Download> downloadHelm = projectTasks.register(
				"downloadHelm",
				Download.class,
				createDownloadConfigurer(project, extension)
		);

		TaskProvider<HelmInitTask> helmInit = projectTasks.register(
				"helmInit",
				HelmInitTask.class,
				helmInitTask -> {
					helmInitTask.dependsOn(downloadHelm);
					HelmSpec.copy(extension, helmInitTask);
				}
		);

		TaskProvider<RepoSyncTask> helmRepoSync = projectTasks.register(
				"helmRepoSync",
				RepoSyncTask.class,
				repoSyncTask -> {
					repoSyncTask.dependsOn(helmInit);
					repoSyncTask.setRepositories(extension.getRepositories());
					HelmSpec.copy(extension, repoSyncTask);
				}
		);

		TaskProvider<HelmBuildTask> helmChartBuild = projectTasks.register(
				"helmChartBuild",
				HelmBuildTask.class,
				helmBuildTask -> {
					helmBuildTask.dependsOn(helmRepoSync);
					helmBuildTask.setExpansions(extension.getExpansions());
					HelmSpec.copy(extension, helmBuildTask);
				});
		projectTasks.named(BasePlugin.ASSEMBLE_TASK_NAME, task -> task.dependsOn(helmChartBuild));
		TaskProvider<HelmTestTask> helmChartTest = projectTasks.register("helmChartTest", HelmTestTask.class, helmTestTask -> {
			helmTestTask.dependsOn(helmChartBuild);
			HelmSpec.copy(extension, helmTestTask);
		});
		projectTasks.named(LifecycleBasePlugin.CHECK_TASK_NAME, task -> task.dependsOn(helmChartTest));

		TaskProvider<HelmDeployTask> helmDeploy = projectTasks.register("helmDeploy", HelmDeployTask.class, helmDeployTask -> {
			helmDeployTask.onlyIf(element -> extension.getDeployTo() != null);
			helmDeployTask.dependsOn(helmChartTest);
		});
		projectTasks.named(BasePlugin.UPLOAD_ARCHIVES_TASK_NAME, task -> task.dependsOn(helmDeploy));
	}

	private Action<Download> createDownloadConfigurer(Project project, HelmSpec helmSpec) {
		return download -> {
			download.src(helmSpec.getHelmDownloadUrl());
			download.dest(getHelmDownloadFile(helmSpec));
			download.overwrite(false);
			// gradle requires an anonymous class for actions for UP-TO-DATE checks to work.
			//noinspection Convert2Lambda
			download.doFirst(new Action<Task>() {
				@Override
				public void execute(Task task) {
					project.delete(helmSpec.getHelmExecutableDirectory());
				}
			});
			// gradle requires an anonymous class for actions for UP-TO-DATE checks to work.
			//noinspection Convert2Lambda
			download.doLast("extractHelm", new Action<Task>() {
				@Override
				public void execute(Task task) {
					project.copy(copySpec -> {
						copySpec.from(project.provider(() -> project.tarTree(getHelmDownloadFile(helmSpec))));
						copySpec.into(helmSpec.getHelmExecutableDirectory());
						copySpec.eachFile(fileCopyDetails -> {
							fileCopyDetails.setPath(fileCopyDetails.getName());
						});
						copySpec.setIncludeEmptyDirs(false);
					});
				}
			});
		};
	}

	public static File getHelmDownloadFile(HelmSpec helmSpec) {
		return new File(helmSpec.getHelmExecutableDirectory(), "helm.tar.gz");
	}

	public static ExecSpec configureFromExtension(ExecSpec spec, HelmSpec helmSpec) {
		spec.setWorkingDir(helmSpec.getHelmExecutableDirectory());
		spec.setExecutable(getHelmExecutable(helmSpec));
		spec.args("--home=" + helmSpec.getHelmHomeDirectory()
				.getAbsolutePath());
		return spec;
	}

	public static Action<ExecSpec> configureFromExtension(HelmSpec helmSpec, Object... args) {
		return execSpec -> {
			configureFromExtension(execSpec, helmSpec);
			execSpec.args(args);
		};
	}

	public static String[] helmExecSuccess(Project project, HelmSpec helmSpec, Object... args) {
		HelmExecResult helmExecResult = helmExec(project, helmSpec, args);
		if (helmExecResult.execResult.getExitValue() != 0) {
			throw new ExecException("Unexpected failed execution:\n" + String.join("\n", helmExecResult.output));
		}
		return helmExecResult.output;
	}

	public static String[] helmExecFail(Project project, HelmSpec helmSpec, Object... args) {
		HelmExecResult helmExecResult = helmExec(project, helmSpec, args);
		if (helmExecResult.execResult.getExitValue() == 0) {
			throw new ExecException("Unexpected successful execution:\n" + String.join("\n", helmExecResult.output));
		}
		return helmExecResult.output;
	}

	public static HelmExecResult helmExec(Project project, HelmSpec helmSpec, Object... args) {
		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		ExecResult execResult = project.exec(execSpec -> {
			configureFromExtension(helmSpec, args).execute(execSpec);
			execSpec.setStandardOutput(outStream);
			execSpec.setErrorOutput(outStream);
			execSpec.setIgnoreExitValue(true);
		});
		String[] lines = outStream.toString().split("\n");
		return new HelmExecResult(execResult, lines);
	}

	public static class HelmExecResult {
		public final ExecResult execResult;
		public final String[] output;

		public HelmExecResult(ExecResult execResult, String[] output) {
			this.execResult = execResult;
			this.output = output;
		}
	}

	public static Object loadYamlSilently(File yamlFile) {
		return loadYamlSilently(yamlFile, false);
	}

	public static Iterable<Object> loadYamlsSilently(File yamlFile) {
		return loadYamlsSilently(yamlFile, false);
	}

	public static Object loadYamlSilently(File yamlFile, boolean emptyObjectInsteadOfRuntimeExceptions) {
		try {
			return YAML.load(new FileInputStream(yamlFile));
		} catch (FileNotFoundException e) {
			if (emptyObjectInsteadOfRuntimeExceptions) {
				return new Object();
			}
			throw new RuntimeException(e);
		} catch (YAMLException parsingError) {
			if (emptyObjectInsteadOfRuntimeExceptions) {
				return new Object();
			}
			throw parsingError;
		}
	}

	public static Iterable<Object> loadYamlsSilently(File yamlFile, boolean emptySetInsteadOfRuntimeExceptions) {
		try {
			return YAML.loadAll(new FileInputStream(yamlFile));
		} catch (FileNotFoundException e) {
			if (emptySetInsteadOfRuntimeExceptions) {
				return Collections.emptySet();
			}
			throw new RuntimeException(e);
		} catch (YAMLException parsingError) {
			if (emptySetInsteadOfRuntimeExceptions) {
				return Collections.emptySet();
			}
			throw parsingError;
		}
	}
}