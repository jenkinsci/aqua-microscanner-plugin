# Aqua Security Microscanner Jenkins Plugin #

This is a Jenkins plugin for calling the Aqua Microscanner to scan a Docker image

## Prerequisites for the plugin to be operational ##

1. Docker must be installed on the same machine Jenkins is installed in because the scanner itself is deployed via a Docker container.
2. The *jenkins* user must be added to the *docker* group so it has permission to run Docker:

     ```
     sudo usermod -aG docker jenkins
     ```
     

## Usage of plugin in Jenkins ##
* In the global configuration page ("Manage Jenkins"/"Configure System") in the section for this plugin, enter values for the Aqua API url, the user name, the password and a timeout value in seconds. The build step will fail if scanning does not terminate within the timeout value. A value of 0 will cause the default timeout value, 300 seconds, to be used.
* In the configuration page for your project, add an "Aqua Security" step from the "Add build step" dropdown list. Choose between a local image or a hosted image. Enter the image path (including the tag) of the image that is to be scanned, and in the case of a hosted image, also enter the registry name. These values can be entered with $VARIABLE syntax on environment variables.
* When run successfully, an artifact named "scanout.html" will be created in the project's workspace. If more than one "Aqua Security" step is added to a build, the additional artifact will be suffixed with consecutive numbers.

## Building the plugin (instructions for Ubuntu)##

* If JDK 7 is not installed, install it
```
     sudo apt-get update
     sudo apt-get install openjdk-7-jdk
```

* Installing Maven3 (must be 3)
 *   On Ubuntu 14.04
 ```
      sudo add-apt-repository ppa:natecarlson/maven3
      sudo apt-get update
      sudo apt-get install maven3
      sudo ln -s /usr/bin/mvn3 /usr/bin/mvn
 ```
 *   On Ubuntu 15.10
 ```
      sudo apt-get update
      sudo apt-get install maven
 ```

*  Build

   When in the root directory, where *pom.xml* resides:
```
     mvn package
```
   Note: the first time this command is invoked, many downloads will occur and it will take quite some time.

## Installing manually ##
Copy the *target/aqua-docker-scanner.hpi* file to *$JENKINS/plugins/* where *JENKINS* is the Jenkins root directory, by default it is */var/lib/jenkins/*.

Restart Jenkins:
```
     sudo /etc/init.d/jenkins restart
```

## Publicly releasing a new version to jenkins-ci.org ##
See https://wiki.jenkins-ci.org/display/JENKINS/Hosting+Plugins#HostingPlugins-Releasingtojenkinsci.org. It describes several alternatives, use the following:

1. If not already done, create a *settings.xml* file with your credentials as described
2. Execute and accept defaults for prompts :
```
    mvn release:prepare release:perform
````
