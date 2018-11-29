<b>Apama Connectivity Plugin for Apache Pulsar.</b>

Apache Pulsar is an open-source distributed pub-sub messaging system. See https://pulsar.apache.org/ for detailed information.

This plugin provides a pulsar transport, which can be used to communicate with the Pulsar distributed pub-sub messaging system. Pulsar messages can be transformed to and from Apama events by listening for and sending events to channels such as prefix:topic (where the prefix is configurable).
It is a non-opinionated view of the Pulsar connectivity. You can modify the connection properties as per your requirements and performance needs. For more details about configurable properties of Pulsar client please refer to the Client API document https://pulsar.apache.org/api/client/.

The connectivty plugin is written in Java and uses Pulsar Java client libraries for connection. Please refer to Apama Documentations to check how to create custom Connectivity plugins for Apama applications. 

<i> Apama Community Edition > Apama Documentation > Connecting Apama Applications to External Components > Working with Connectivity Plug-ins > Developing Connectivity Plug-ins</i>

<b>How to use: </b>

You can download the existing executables from <i>./bin</i> folder which contains <b>pulsar-connector-1.0-Beta.jar</b> (which is the connectivity plugin), <b>pulsar-client-2.2.0.jar</b> (Pulsar Java Client) and it's dependencies. Or you could also build the project from sources <i>(./src folder)</i> using the Apache Maven configuration <i>(./pom.xml)</i> file supplied.

<b>How to build using Maven:</b>

Create a Maven project using the POM.xml file provided with this plugin. You can change/update project version, pulsar version <i>(Note: Have tested only against Pulsar 2.2.0 version).</i>

The following dependencies <i><b>ap-connectivity-plugins-impl, ap-util and connectivity-plugins-api are not present in Maven Central</b></i> as they are Apama's version specific jars. To build the project you have to either delete these entires from POM.xml file and add these jars from Apama installation ($Apama_Home/lib) to your project's build path. Or you might choose to add these Jars to your local maven repository from your local Apama Installation. See https://maven.apache.org/guides/mini/guide-3rd-party-jars-local.html.
If using from your local maven repository, use the version number you've provided while adding those jars.

<b>run mvn clean install</b> to generate pulsar-connector and pulsar dependencies in target folder.

<b>Running Sample:</b>

The sample applications present inside ./Demo_Application folder shows usage of this connectivity plugin to send and receive messages/event via Apache Pulsar.

<b>1. Setup and Run Standalone Apache Pulsar Cluster:</b>
	Follow the instructions as provided in https://pulsar.apache.org/docs/en/standalone/.  Note: Pulsar is available only in MacOS and Linux (at the time of writing).
	

<i>Note: pulsar_test_receiver and pulsar_test_sender Apama applications are created using SoftwareAG designer tool and exported using engine_deploy tool (present in Apama Installation).</i>
		For info about creating Apama EPL application using SoftwareAG Designer please refer <i>Apama Documentation >  Using Apama with Software AG Designer.</i>
		For info about <b>engine_deploy</b> tool please refer: <i>Apama Documentation > Deploying and Managing Apama Applications > Correlator Utilities Reference > Deploying a correlator</i>

<b>2. Running application to receive message from Apache Pulsar and process in EPL File:</b>
	pulsar_test_receiver has a PulsarTest.mon file which defines a PulsarSendEvent, logs whenever it receives message from "pulsar:my-topic" channel and also sends Acknowledgement of messages when it's done processing this (optional configuration can be modified via configuration file).
	The Pulsar Connecitivity chain and other configurations are present inside pulsar_test_receiver/config/connectivity/UserConnectivity/. You can modify configurations as per your system and path. (Check below to see list of properties you need to modify to run the sample).
	
	Once all properties (including pulsar serviceURL has been modified), you can run the application:
	a) Open command line tool and define Apama Environment properties by running $APAMA_HOME/bin/apama_env
	b) Start the correlator and provide pulsar_test_receiver as user configuration directory
		$correlator --config ./pulsar_test_receiver
	c) This will start a correlator on default port and inject the .mon files.

<b>3. 	Running application to send message from Apache Pulsar:</b>
	pulsar_test_sender has a PulsarTest.mon file which defines a PulsarSendEvent (same as the one defined by receiver application. Waits for 60 secs before sending messages in loop to "pulsar:my-topic" channel.
	The Pulsar Connecitivity chain and other configurations are present inside pulsar_test_sender/config/connectivity/UserConnectivity/. You can modify configurations as per your system and path. (Check below to see list of properties you need to modify to run the sample).
	
	Once all properties (including pulsar serviceURL has been modified), you can run the application:
	a) Open command line tool and define Apama Environment properties by running $APAMA_HOME/bin/apama_env
	b) Start the correlator and provide pulsar_test_receiver as user configuration directory
		$correlator --config ./pulsar_test_sender  (can give differnt port number using -p 8080)
	c) This will start a correlator on default port and inject the .mon files.

<b>Configurations:</b>

Both sender and receiver application has same set of properties which can be modified:

<i>Important properties:</i>

	a)	<b>UserConnectivity.properties > MY_WORK</b> = Path to the bin folder, where all the pulsar connectivity and other generated jars are present.
	b)	<b>UserConnectivity.plugins.yaml</b>: Defines the pulsarTransport. All pulsarConnectivity jars are configured here along with the pulsarChainManager (refer Apama Doc for Dynamic ChainManagers)
	c)	<b>UserConnectivity.chains.yaml:</b> Defines the pulsar Connectivity Chain (differnt for sender and receiver)
		  i) under ManagerConfig: 
			  <i><b>serviceURL</b></i> is mandatory, contains pulsar cluster endpoints.
			  <i><b>channelPrefix:</b></i> default value is "pulsar:"
			  remaining properties has to be same as that defined by pulsar client API.
			  where entries under managerConfig defines the pulsarClient properties and managerConfig > producerConfig (or receiverConfig) defines properties for ProducerBuilder and ConsumerBuilder class. (refer Pulsar Java Client API ProducerBuilder<T> and ConsumerBuilder<T>).
			
			  <b>Code uses ProducerBuilder.loadConf(Map<String,Object> config) and ConsumerBuilder.loadConf(Map<String,Object> config) methods to load all the properties. <i>Note: Make sure Propertiey names are same as accepted by Pulsar Clients. As code doesn't validate the properties and it's values.</i></b>
		  ii) You can also modify the pulsarChain, by adding Codecs (e.g. MapperCodec, JSONCodec to transform the data  before sending data to EPL file or before sending to pulsar Cluster). refer apama documentation for Codecs and transformations.

<b>Channel and Other details:</b>

In EPL file, the channel you are subscribing to (for receiver) and sending data towards (for producer) should be a combination of $CHANNEL_PREFIX:$PULSAR_TOPIC_NAME e.g. "pulsar:My_Custom_Topic". $CHANNEL_PREFIX by default is "pulsar:" or can be configured in UserConnectivity.chains.yaml by using same name field "channelPrefix".

This connectivity transport sends and recieve data from pulsar as byte[].

<b>******************* TODO: Enable reliable communication via acknowledgement in EPL. producer topic is read from config file as of now, convert it to be dynamic ******************** </b>
