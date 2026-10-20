package com.example.studiomobile.build;

import android.content.Context;
import com.example.studiomobile.build.process.BuildProcessRunner;
import com.example.studiomobile.build.stages.ApkAlignmentStage;
import com.example.studiomobile.build.stages.ApkPackagingStage;
import com.example.studiomobile.build.stages.ApkSigningStage;
import com.example.studiomobile.build.stages.NativeBuildStage;
import com.example.studiomobile.build.stages.DependencyResolutionStage;
import com.example.studiomobile.build.stages.GeneratedSourcesStage;
import com.example.studiomobile.build.stages.ProjectModulesStage;
import com.example.studiomobile.build.stages.CodeShrinkingStage;
import com.example.studiomobile.build.stages.DexCompilationStage;
import com.example.studiomobile.build.stages.ResourceCompilationStage;
import com.example.studiomobile.build.stages.ResourceLinkingStage;
import com.example.studiomobile.build.stages.SourceCompilationStage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the full on-device APK pipeline (no Gradle) and never reports success without a verified signed APK.
 * Libraries declared in build.gradle(.kts) are resolved from the local cache or, unless the configuration is offline, downloaded.
 */
public final class LocalBuildPipeline implements BuildPipeline {
    private final Context context; private volatile boolean cancelled; private volatile Process activeProcess;
    public LocalBuildPipeline(Context context) { this.context=context.getApplicationContext(); }

    @Override public BuildResult build(BuildConfiguration configuration, BuildLogger logger) {
        if (logger == null) logger = new BuildLogger() {
            @Override public void onInfo(String message) { }
            @Override public void onWarning(String message) { }
            @Override public void onError(String message, Throwable cause) { }
        };
        RecordingBuildLogger recordingLogger = new RecordingBuildLogger(logger);
        logger = recordingLogger;
        cancelled=false; File output=configuration.getOutputDirectory();
        if(!output.exists()&&!output.mkdirs())return failed("Could not create APK output directory: "+output);
        File work=new File(context.getCacheDir(),"local-build-"+System.currentTimeMillis());
        if(!work.mkdirs())return failed("Could not create temporary build directory: "+work);
        BuildStageContext stageContext=new BuildStageContext(configuration,work);
        String activeStage = "INITIALIZATION";
        try {
            report(logger,"Validating project structure…"); ProjectValidator.ValidationReport validation=new ProjectValidator().validate(configuration.getProjectDirectory());
            for(String warning:validation.getWarnings())logger.onWarning(warning);
            if(!validation.isValid()){for(String error:validation.getErrors())logger.onError(error,null);return failed("Project validation failed. Fix the listed errors before building.");}
            activeStage = "JAVA LEVEL CHECK";
            report(logger,"Checking Java level (up to Java "+JavaLanguageLevel.REQUIRED+")…"); String levelProblem=JavaLanguageLevel.check(configuration.getProjectDirectory(),logger);
            if(levelProblem!=null)return failed(levelProblem);
            if(configuration.isOfflineMode())report(logger,"Offline mode enabled. Network access will not be used.");
            report(logger,"Checking local compiler toolchain…"); List<String> missing=new BuildToolchainInspector(context).inspect();
            if(!missing.isEmpty()){for(String item:missing)logger.onError(item,null);logger.onError("Build stopped before compilation. Install verified toolchain assets in app-private files/build-tools.",null);return failed("Local build toolchain is incomplete; no APK was generated.");}
            activeStage = "JAVA COMPILER SELF-TEST";
            report(logger,"Verifying Java "+JavaLanguageLevel.REQUIRED+" compiler…"); String compilerProblem=JavaCompilerPreflight.check(new File(context.getFilesDir(),"build-tools"),configuration.getProjectDirectory(),new File(work,"preflight"));
            if(compilerProblem!=null){logger.onError(compilerProblem,null);logger.onError("Build stopped before compilation. No APK was generated.",null);return failed("Java "+JavaLanguageLevel.REQUIRED+" compiler check failed; no APK was generated. "+compilerProblem);}
            for(BuildStage stage:createStages(configuration)){
                activeStage = stage.getName();
                if(cancelled)return new BuildResult(BuildResult.Status.CANCELLED,null,"Build cancelled by user.");
                report(logger,stage.getName()+"…"); stage.execute(stageContext,logger);
            }
            File apk=stageContext.getOutput("signedApk");
            if(apk==null||!apk.isFile()||apk.length()==0)return failed("Pipeline finished without a signed APK output.");
            return new BuildResult(BuildResult.Status.SUCCESS,apk,"Debug APK built and signature verified.");
        } catch(Exception exception){
            if (cancelled) {
                logger.onWarning("Build cancellation requested during stage: " + activeStage);
                return new BuildResult(BuildResult.Status.CANCELLED, null, "Build cancelled by user. Stage: " + activeStage);
            }
            String detail = "Build failed in stage " + activeStage + ": " + exception.getClass().getName() + ": " + String.valueOf(exception.getMessage());
            logger.onError(detail, exception);
            File report = BuildFailureReport.write(com.example.studiomobile.workspace.StudioStorage.errorLogsDir(context), activeStage, exception, detail, recordingLogger.getTranscript());
            if (report != null) logger.onError("Full diagnostic report saved to: " + report.getAbsolutePath(), null);
            else logger.onWarning("Could not save diagnostic report; original exception was still sent to the build logger.");
            return failed(detail + (report == null ? "" : " Diagnostic report: " + report.getAbsolutePath()));
        } finally{activeProcess=null; BuildCacheCleaner.deleteTree(work);}   // the signed APK / AAB live in the output folder, not here
    }
    private List<BuildStage> createStages(BuildConfiguration configuration){
        File tools=new File(context.getFilesDir(),"build-tools"); installProcessorApi(tools); BuildExecutableResolver.registerNativeLibraryDirectory(new File(context.getApplicationInfo().nativeLibraryDir)); BuildProcessRunner runner=new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
            @Override public void onStarted(Process process) { activeProcess = process; }
            @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
        }); List<BuildStage> stages=new ArrayList<>();
        stages.add(new com.example.studiomobile.build.stages.NativePreflightStage(tools));
        stages.add(new DependencyResolutionStage(com.example.studiomobile.workspace.StudioStorage.localLibsDir(context)));
        stages.add(new ProjectModulesStage());
        stages.add(new com.example.studiomobile.build.stages.JsxTranspileStage(this::openJsTranspiler));
        stages.add(new ResourceCompilationStage(tools,runner));
        stages.add(new ResourceLinkingStage(tools,runner));
        stages.add(new GeneratedSourcesStage());
        stages.add(new com.example.studiomobile.build.stages.DartAppStage(context.getFilesDir(), new File(context.getApplicationInfo().nativeLibraryDir), context.getCacheDir(), this::installDartSdk));   // only acts when src/main/dart/main.dart exists
        stages.add(new SourceCompilationStage(tools,runner));
        stages.add(new com.example.studiomobile.build.stages.CrashRiskCheckStage());
        stages.add(ProguardSettings.read(configuration.getProjectDirectory()).isMinifyEnabled() ? new CodeShrinkingStage(tools) : new DexCompilationStage(tools,runner));
        stages.add(new NativeBuildStage(tools,runner,ndkProvisioner()));
        stages.add(new ApkPackagingStage());
        stages.add(new ApkAlignmentStage(tools,runner));
        stages.add(new ApkSigningStage(tools,runner));
        if(configuration.isBundleRequested())stages.add(new com.example.studiomobile.build.stages.AabPackagingStage(tools,runner));
        return stages;
    }
    /** Copies the bundled javax.annotation.processing / javax.lang.model API jar next to the other tools; D8 needs it as a library when it converts processors. */
    private void installProcessorApi(File tools){
        File target=new File(tools,"java-compiler-api.jar"); if(target.length()>0)return;
        try(java.io.InputStream in=context.getAssets().open("build-tools/java-compiler-api.jar")){
            if(!tools.isDirectory()&&!tools.mkdirs())return;
            java.nio.file.Files.copy(in,target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(java.io.IOException ignored){ /* only annotation processing needs it, and it reports its own failure */ }
    }
    private static void report(BuildLogger logger,String message){if(logger!=null)logger.onInfo(message);}
    private static BuildResult failed(String message){return new BuildResult(BuildResult.Status.FAILED,null,message==null?"Build failed.":message);}
    @Override public void cancel(){cancelled=true;Process process=activeProcess;if(process!=null)process.destroy();}

    /** Catalog-backed NDK downloader; null when the bundled catalog cannot be trusted (the stage then reports the missing NDK). */
    private com.example.studiomobile.build.nativecode.NativeNdkProvisioner ndkProvisioner(){
        try(java.io.InputStream in=context.getAssets().open("build-tools/catalog.json")){
            com.example.studiomobile.build.dependency.BuildToolCatalog catalog=com.example.studiomobile.build.dependency.BuildToolCatalog.readVerified(in,com.example.studiomobile.build.dependency.BuildToolCatalog.BUNDLED_CATALOG_SHA256);
            return new com.example.studiomobile.build.nativecode.NativeNdkProvisioner(context.getFilesDir(),catalog,java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS));
        }catch(Exception ignored){return null;}
    }

    /** Downloads the Dart SDK files once (checksum-pinned catalog entry), the same way "Run with Dart" does. */
    private boolean installDartSdk(BuildLogger logger) throws Exception {
        try (java.io.InputStream in = context.getAssets().open("build-tools/catalog.json")) {
            com.example.studiomobile.build.dependency.BuildToolCatalog catalog = com.example.studiomobile.build.dependency.BuildToolCatalog.readVerified(in, com.example.studiomobile.build.dependency.BuildToolCatalog.BUNDLED_CATALOG_SHA256);
            return new com.example.studiomobile.dart.DartProvisioner(context.getFilesDir(), catalog, java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS)).install(logger) != null;
        }
    }

    /** Opens the JSX/TypeScript transpiler (Sucrase in QuickJS). Only called when the project has .jsx/.tsx/.ts files in src/main/assets. */
    private com.example.studiomobile.build.web.JsTranspiler openJsTranspiler() throws java.io.IOException {
        java.io.InputStream bundle;
        try { bundle = context.getAssets().open("build-tools/web/sucrase.js"); }
        catch (java.io.IOException missing) { throw new java.io.IOException("The JSX transpiler (build-tools/web/sucrase.js) is missing from this build of Studio Mobile.", missing); }
        return new com.example.studiomobile.build.web.QuickJsSucraseTranspiler(bundle);
    }
}
