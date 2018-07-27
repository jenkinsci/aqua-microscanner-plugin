package org.jenkinsci.plugins.aquadockerscannerbuildstep;

import hudson.Launcher;
import hudson.EnvVars;
import hudson.Launcher.ProcStarter;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.ArgumentListBuilder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.FileNotFoundException;
import java.util.UUID;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * This class does the actual execution..
 *
 * @author Oran Moshai
 */
public class ScannerExecuter {

	public static int execute(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, String artifactName,
			String microScannerToken, String imageName, String notCompliesCmd, String outputFormat, boolean checkonly, boolean caCertificates){

		PrintStream print_stream = null;
		try {
			final EnvVars env = build.getEnvironment(listener);
			int passwordIndex = 2;
			String microscannerDockerfilePath = workspace.toString() + "/Dockerfile.microscanner";

			StringBuilder microScannerDockerfileContent = new StringBuilder();

			microScannerDockerfileContent.append("FROM " + imageName + "\n");
			microScannerDockerfileContent.append("ADD https://get.aquasec.com/microscanner .\n");
			microScannerDockerfileContent.append("USER 0\n");
			microScannerDockerfileContent.append("RUN chmod +x microscanner\n");
			microScannerDockerfileContent.append("ARG token\n");
			microScannerDockerfileContent.append("RUN ./microscanner ${token} ").append("json".equalsIgnoreCase(outputFormat) ? "" : "--html ");
			if (checkonly)
			{
				microScannerDockerfileContent.append("--continue-on-failure ");
			}
			if (!caCertificates)
			{
				microScannerDockerfileContent.append("--no-verify");
			}

			String microScannerDockerfile = microScannerDockerfileContent.toString();
			FilePath dockerTarget = new FilePath(workspace, "Dockerfile.microscanner");
			try
			{
				dockerTarget.write(microScannerDockerfile, "UTF-8");
			}
			catch (Exception e)
			{
				listener.getLogger().println("Failed to save MicroScanner Dockerfile.");
			}

			//Build docker args
			ArgumentListBuilder args = new ArgumentListBuilder();
			String buildArg = "--build-arg=token=" + microScannerToken;
			UUID uniqueId = UUID.randomUUID();
			String uniqueIdStr = uniqueId.toString().toLowerCase();
			args.add("docker", "build", buildArg, "--no-cache", "-t", "aqua-ms-" + uniqueIdStr);
			args.add("-f", microscannerDockerfilePath, workspace.toString());

			String outFileName = "out";
			File outFile = new File(build.getRootDir(), outFileName);
			Launcher.ProcStarter ps = launcher.launch();
			ps.cmds(args);
			ps.stdin(null);
			ps.stderr(listener.getLogger());
			print_stream = new PrintStream(outFile, "UTF-8");
			ps.stdout(print_stream);
			ps.quiet(true);
			boolean[] masks = new boolean[ps.cmds().size()];
			masks[passwordIndex] = true; // Mask out password

			ps.masks(masks);
			listener.getLogger().println("Aqua MicroScanner in progress...");
			int exitCode = ps.join(); // RUN !

			// Copy local file to workspace FilePath object (which might be on remote
			// machine)
			FilePath target = new FilePath(workspace, artifactName);
			FilePath latestTarget = new FilePath(workspace, "scanlatest." + outputFormat );
			FilePath outFilePath = new FilePath(outFile);
			outFilePath.copyTo(target);
			String scanOutput = target.readToString();
			if (exitCode == 1)
			{
				listener.getLogger().println(scanOutput);
			}
			cleanBuildOutput(scanOutput, target, latestTarget, listener, imageName);

			// Possibly run a shell command on non compliance
			if (exitCode == AquaDockerScannerBuilder.DISALLOWED_CODE && !notCompliesCmd.trim().isEmpty()) {
				ps = launcher.launch();
				args = new ArgumentListBuilder();
				args.add("bash", "-c", notCompliesCmd);
				ps.cmds(args);
				ps.stdin(null);
				ps.stderr(listener.getLogger());
				ps.stdout(listener.getLogger());
				ps.join(); // RUN !

			}
			//Cleanup
			dockerTarget.delete(); // Clean MicroScanner Dockerfile
			ps = launcher.launch();
			ps.cmdAsSingleString("docker rmi aqua-ms-"+ uniqueIdStr);
			ps.quiet(true);
			ps.join();
			return exitCode;

		} catch (RuntimeException e) {
			listener.getLogger().println("RuntimeException:" + e.toString());
			return -1;
		} catch (Exception e) {
			listener.getLogger().println("Exception:" + e.toString());
			return -1;
		} finally {
			if (print_stream != null) {
				print_stream.close();
			}
		}
	}

	//Read dockerbuild output and saving only html/json output.
	private static boolean cleanBuildOutput(String scanOutput, FilePath target, FilePath latestTarget, TaskListener listener, String title) {
		int htmlStart = scanOutput.indexOf("<!DOCTYPE html>");
		int htmlEnd = scanOutput.lastIndexOf("</html>") + 7;
		if (htmlStart > -1) {
			scanOutput = scanOutput.substring(htmlStart, htmlEnd);
			scanOutput = scanOutput.replace("<h1>Scan Report: </h1>", "<h1>Scan Report: " + title + "</h1>");
		} else {
			int jsonStart = scanOutput.indexOf("\"scan_started\"");
			int jsonEnd = scanOutput.lastIndexOf("}") + 1;
			if (jsonStart > -1) {
				scanOutput = "{\n  " +scanOutput.substring(jsonStart, jsonEnd);
			}
		}
		try
		{
			//The latest target will be overwritten with each run, but can be used from pipelines to validate latest run
			latestTarget.write(scanOutput, "UTF-8");
			target.write(scanOutput, "UTF-8");
		}
		catch (Exception e)
		{
			listener.getLogger().println("Failed to save MicroScanner HTML report.");
		}

		return true;
	}

}
