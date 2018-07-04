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
			String microScannerToken, String imageName, String notCompliesCmd, boolean checkonly, boolean caCertificates){

		PrintStream print_stream = null;
		try {
			final EnvVars env = build.getEnvironment(listener);
			int passwordIndex = 2;
			String microscannerDockerfilePath = workspace.toString() + "/Dockerfile.microscanner";

			StringBuilder microScannerDockerfileContent = new StringBuilder();

			microScannerDockerfileContent.append("FROM " + imageName + "\n");
			microScannerDockerfileContent.append("USER root" + "\n");
			microScannerDockerfileContent.append("WORKDIR /" + "\n");
      microScannerDockerfileContent.append("RUN if [ ! -d /etc/ssl/certs/ ]; then \\" + "\n");
      microScannerDockerfileContent.append("  PACKAGE_MANAGER=$(basename $(command which apk apt yum false 2>/dev/null | head -n1)); \\" + "\n");
      microScannerDockerfileContent.append("  if [ ${PACKAGE_MANAGER} = apk ]; then \\" + "\n");
      microScannerDockerfileContent.append("    COMMAND='apk --no-cache add'; \\" + "\n");
      microScannerDockerfileContent.append("  elif [ ${PACKAGE_MANAGER} = apt ]; then \\" + "\n");
      microScannerDockerfileContent.append("    COMMAND='apt update && apt install --no-install-recommends -y'; \\" + "\n");
      microScannerDockerfileContent.append("  elif [ ${PACKAGE_MANAGER} = yum ]; then \\" + "\n");
      microScannerDockerfileContent.append("    COMMAND='yum install -y'; \\" + "\n");
      microScannerDockerfileContent.append("  else \\" + "\n");
      microScannerDockerfileContent.append("    echo '/etc/ssl/certs/ not found and package manager not apk, apt, or yum. Aborting' >&2; \\" + "\n");
      microScannerDockerfileContent.append("    exit 1; \\" + "\n");
      microScannerDockerfileContent.append("  fi; \\" + "\n");
      microScannerDockerfileContent.append("  eval ${COMMAND} ca-certificates; \\" + "\n");
      microScannerDockerfileContent.append("fi" + "\n");
			microScannerDockerfileContent.append("ADD https://get.aquasec.com/microscanner .\n");
			microScannerDockerfileContent.append("RUN chmod +x microscanner\n");
			microScannerDockerfileContent.append("ARG token\n");
			microScannerDockerfileContent.append("RUN ./microscanner ${token} --html ");
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
			FilePath outFilePath = new FilePath(outFile);
			outFilePath.copyTo(target);
			String scanOutput = target.readToString();
			if (exitCode == 1)
			{
				listener.getLogger().println(scanOutput);
			}
			cleanBuildOutput(scanOutput, target, listener);

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

	//Read dockerbuild output and saving only html output.
	private static boolean cleanBuildOutput(String scanOutput, FilePath target, TaskListener listener) {
		int htmlStart = scanOutput.indexOf("<!DOCTYPE html>");
		int htmlEnd = scanOutput.lastIndexOf("</html>") + 7;
		scanOutput = scanOutput.substring(htmlStart,htmlEnd);
		try
		{
			target.write(scanOutput, "UTF-8");
		}
		catch (Exception e)
		{
			listener.getLogger().println("Failed to save MicroScanner HTML report.");
		}

		return true;
	}

}
