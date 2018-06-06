package org.jenkinsci.plugins.aquadockerscannerbuildstep;

import hudson.Launcher;
import hudson.EnvVars;
import hudson.Launcher.ProcStarter;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.ArgumentListBuilder;
import java.io.FileWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.io.PrintStream;
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
			String microScannerToken, String imageName, String notCompliesCmd, boolean checkonly, boolean caCertificates) {

		PrintStream print_stream = null;
		try {
			final EnvVars env = build.getEnvironment(listener);
			int passwordIndex = 2;
			String microscannerDockerfilePath = workspace.toString() + "/Dockerfile.microscanner";
			//Cleanup
			cleanMicroScannerDockerFile(microscannerDockerfilePath);
			//
			try
			{
				FileWriter f = new FileWriter(new File(microscannerDockerfilePath));
				f.write("FROM " + imageName + "\n");
				f.write("ADD https://get.aquasec.com/jp/microscanner .\n");
				f.write("RUN chmod +x microscanner\n");
				f.write("ARG token\n");
				f.write("RUN ./microscanner ${token} --html ");
				if (checkonly)
				{
					f.write("--continue-on-failure ");
				}
				if (!caCertificates)
				{
					f.write("--no-verify");
				}
				f.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
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
			if (exitCode == 1)
			{
				String error = readOutput(build, outFileName);
				listener.getLogger().println(error);
			}
			// Copy local file to workspace FilePath object (which might be on remote
			// machine)

			FilePath target = new FilePath(workspace, artifactName);
			try{
				cleanBuildOutput(build, outFileName);}
			catch (Exception e){
				listener.getLogger().println("");
			}

			FilePath outFilePath = new FilePath(outFile);
			outFilePath.copyTo(target);

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
			cleanMicroScannerDockerFile(microscannerDockerfilePath);
			cleanMicroScannerImage(uniqueIdStr);
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
	//Delete microscanner Dockerfile.
	private static boolean cleanMicroScannerDockerFile(String microscannerDockerfilePath) {
		File file = new File(microscannerDockerfilePath);
		file.delete();

		return true;
	}
	//Clean microscanner unique build image.
	private static boolean cleanMicroScannerImage(String uniqueIdStr) {
		try
		{
			Runtime.getRuntime().exec(new String[]{"bash","-c","docker rmi aqua-ms-"+ uniqueIdStr});
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}

		return true;
	}
	//Read dockerbuild output and saving only html output.
	private static boolean cleanBuildOutput(Run<?, ?> build, String outFileName) {
		String output = readOutput(build, outFileName);

		int htmlStart = output.indexOf("<!DOCTYPE html>");
		int htmlEnd = output.lastIndexOf("</html>") + 7;
		output = output.substring(htmlStart,htmlEnd);
		try {
			FileWriter f = new FileWriter(new File(build.getRootDir(), outFileName));
			f.write(output);
			f.close();
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		return true;
	}
	//reads job output and return string.
	private static String readOutput(Run<?, ?> build, String outFileName)
	{
		String output = "";
		try
		{
			output = new String ( Files.readAllBytes( Paths.get(build.getRootDir().toString() + "/" + outFileName) ) );
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		return output;
	}
}
