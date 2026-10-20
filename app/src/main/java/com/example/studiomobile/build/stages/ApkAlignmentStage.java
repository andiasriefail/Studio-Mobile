package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.Arrays;

/** Aligns the unsigned APK before signing, as required by Android APK packaging workflows. */
public final class ApkAlignmentStage implements BuildStage {
    private final File tools; private final BuildProcessRunner runner;
    public ApkAlignmentStage(File tools, BuildProcessRunner runner) { this.tools=tools; this.runner=runner; }
    @Override public String getName() { return "APK alignment (zipalign)"; }
    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File input=context.getOutput("unsignedApk"); if(input==null||!input.isFile())throw new IllegalStateException("Unsigned APK is missing.");
        File aligned=new File(context.getWorkingDirectory(),"aligned.apk");
        java.util.List<File> candidates = com.example.studiomobile.build.BuildExecutableResolver.candidates(tools, "zipalign");
        StringBuilder failures = new StringBuilder();
        boolean success = false;
        for (File zipalign : candidates) {
            if (!zipalign.isFile() || !zipalign.canExecute()) continue;
            if (aligned.exists() && !aligned.delete()) throw new IllegalStateException("Could not clear stale aligned APK before fallback attempt.");
            try {
                BuildProcessRunner.Result result=runner.run(Arrays.asList(zipalign.getAbsolutePath(),"-f","-p","4",input.getAbsolutePath(),aligned.getAbsolutePath()),context.getWorkingDirectory(),120,logger);
                if(result.isSuccessful()&&aligned.isFile()&&aligned.length()>0) { success=true; if(logger!=null)logger.onInfo("APK alignment succeeded using "+zipalign.getAbsolutePath()); break; }
                failures.append(zipalign.getAbsolutePath()).append(" exit=").append(result.getExitCode()).append(result.isTimedOut()?" timeout; ":"; ");
            } catch (Exception error) {
                failures.append(zipalign.getAbsolutePath()).append(" ").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage()).append("; ");
            }
        }
        if (!success) {
            // No usable native zipalign: use the in-process aligner. Its output is verified against the real file offsets.
            if (logger != null) logger.onInfo("Native zipalign unavailable or failed (" + (failures.length() == 0 ? "not installed" : failures) + "); using the in-process ZIP aligner.");
            if (aligned.exists() && !aligned.delete()) throw new IllegalStateException("Could not clear stale aligned APK.");
            try { com.example.studiomobile.build.ZipAligner.align(input, aligned, 4, 4096); }
            catch (java.io.IOException error) { throw new IllegalStateException("APK alignment failed. The pipeline will not skip alignment. Native: " + failures + " In-process: " + error.getMessage(), error); }
            success = true;
            if (logger != null) logger.onInfo("APK alignment succeeded using the in-process aligner (offsets verified).");
        } else {
            java.util.List<String> bad = com.example.studiomobile.build.ZipAligner.findMisaligned(aligned, 4, 4096);
            if (!bad.isEmpty()) throw new IllegalStateException("zipalign reported success but entries are misaligned, first: " + bad.get(0));
        }
        context.putOutput("alignedApk",aligned);
    }
}
