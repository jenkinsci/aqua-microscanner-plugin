package org.jenkinsci.plugins.aquadockerscannerbuildstep;

import hudson.model.Action;
import hudson.model.Run;

public class AquaScannerAction implements Action {

    private String resultsUrl;
    private Run<?, ?> build;
    private String artifactSuffix;
    private String title;

    public AquaScannerAction(Run<?, ?> build, String artifactSuffix, String artifactName, String title) {
        this.build = build;
        this.artifactSuffix = artifactSuffix;
        this.resultsUrl = "../artifact/" + artifactName;
        this.title = title;
    }

    @Override
    public String getIconFileName() {
        // return the path to the icon file
        return "/plugin/aqua-microscanner/images/MicroScanner.png";
    }

    @Override
    public String getDisplayName() {
        // return the label for your link
    if (title != null) {
        return title;
    }
	if (artifactSuffix == null) {
	    return "Aqua MicroScanner";
	} else {
	    return "Aqua MicroScanner " + artifactSuffix;
	}
    }

    @Override
    public String getUrlName() {
        // defines the suburl, which is appended to ...jenkins/job/jobname
	if (artifactSuffix == null) {
	    return "aqua-results";
	} else {
	    return "aqua-results-" + artifactSuffix;
	}
    }

    public Run<?, ?> getBuild() {
        return this.build;
    }

    public String getResultsUrl() {
        return this.resultsUrl;
    }
}
