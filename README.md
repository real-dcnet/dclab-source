# DClab
## Dependencies
The following commands install all dependencies required to run Mininet and the ONOS controller:
```
sudo apt install mininet
sudo apt install default-jdk
sudo apt install maven
wget http://repo1.maven.org/maven2/org/onosproject/onos-releases/2.1.0/onos-2.1.0.tar.gz
wget https://sourceforge.net/projects/jgrapht/files/JGraphT/Version%201.4.0/jgrapht-1.4.0.tar.gz
tar -xvf onos-2.1.0.tar.gz
tar -xvf jgrapht-1.4.0.tar.gz
```

## Generating Mininet Topology
The following command generates the nested ring topology in Mininet
```
sudo python python/src/nested_ring.py  [--size NUM]
                            [--hops NUM1 [NUM2 NUM3 ...]]
                            [--fanout NUM]
```
All arguments are optional, with the default values and effects being:


--size   (Default 23) : Number of nodes in each of the two rings

--hops   (Default [1, 5]) : Number of hops for each cycle in the core ring

--fanout (Default 2) : Number of hosts connected to each switch in edge ring

Also note that --hops can take multiple arguments, which can be used to add multiple cycles in the core ring where each goes through switches in a different order.

Running this command starts the Mininet CLI

## Running ONOS Controller
To start ONOS, go to the directory containing onos-2.1.0 that was extracted from the tarball and use the commands:
```
cd onos-2.1.0/bin
./onos-service
```

This starts the service that listens for a connection from Mininet. Then, access the web gui through http://localhost:8181/onos/ui, and use credentials:
```
Username: onos
Password: rocks
```

Once logged in to the gui, enable reactive forwarding and disable LLDP on the applications tab.

### Installing DClab Application
To start the DClab application for ONOS, change directory to dclab-source/dclab, and build the app using:
```
mvn clean install -Dcheckstyle.skip
```

In the target directory this generates an OAR file named onos-app-dclab-2.1.0.oar that can be used by ONOS. Change directory back to onos-2.1.0/bin, and use the command:
```
./onos-app 127.0.0.1 reinstall! <Path to oar>
```

Which installs the DClab application into the ONOS controller and activates it. If the application needs to be uninstalled, use the command:
```
./onos-app 127.0.0.1 uninstall org.onosproject.dclab
```

## Installing JGraphT on ONOS
Since ONOS needs access to the JARs for JGraphT in order to use it during runtime, use the following commands to move the JARs into a directory which ONOS can access. Note that guava JARs are removed since they conflict with some of ONOS's own packages
```
cp jgrapht-1.4.0/*.jar onos-2.1.0/apache-karaf-4.2.3
rm *guava* onos-2.1.8/apache-karaf-4.2.3/deploy
```

## Using the DClab CLI
In order to start the CLI for DClab, execute the following program
```
python python/src/dclab_shell.py
```

Once the interface is running, you can use the following commands
```
create - Start new config with optional topology appended
                 Linear Syntax: create [linear,<length>,<count>]
                 Star Syntax:   create [star,<points>,<count>]
                 Tree Syntax:   create [tree,<depth>,<fanout>,<count>]

append - Append topology to current configuration
                 Linear Syntax: append linear,<length>,<count>
                 Star Syntax:   append star,<points>,<count>
                 Tree Syntax:   append tree,<depth>,<fanout>,<count>

show - Shows current state of the topology configuration

load - Load configuration from a specified file
                 Syntax:        load <file_name>

write - Write current configuration to either a specified file or to default location
                 Syntax:        write [file_name]

run - Run DClab using currently saved configuration file

apply - Write current configuration to default location, then run DClab
```

A typical workflow with the CLI will involve creating some initial topology with create, using append to add on more parameters to the topology, using show to check the topology, and then using apply (equivalent to write followed by run) to apply the overlay via ONOS. Load may also be seen in cases where a configuration file already exists, such as from a write.