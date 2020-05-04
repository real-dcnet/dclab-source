from cmd import Cmd
import json
import os

class DClabShell(Cmd):
    config = []
    config_loc = "../../config/dclab/test_config.json"

    def do_exit(self, inp):
        '''Exit the shell'''
        print("Exiting")
        return True

    def do_create(self, inp):
        '''Start new config with optional topology appended
        Linear Syntax: create [linear,<length>,<count>]
        Star Syntax:   create [star,<points>,<count>]
        Tree Syntax:   create [tree,<depth>,<fanout>,<count>]'''
        self.config = []
        if inp:
            self.do_append(inp)

    def do_append(self, inp):
        '''Append topology to current configuration
        Linear Syntax: append linear,<length>,<count>
        Star Syntax:   append star,<points>,<count>
        Tree Syntax:   append tree,<depth>,<fanout>,<count>'''
        if not inp:
            print("Error: append requires input")
            return
        parsed = inp.split(",")
        if parsed[0] == "linear":
            self.config.append({"type": parsed[0],
                                "length": int(parsed[1]),
                                "count": int(parsed[2])})

        if parsed[0] == "star":
            self.config.append({"type": parsed[0],
                                "points": int(parsed[1]),
                                "count": int(parsed[2])})

        if parsed[0] == "tree":
            self.config.append({"type": parsed[0],
                                "depth": int(parsed[1]),
                                "fanout": int(parsed[1]),
                                "count": int(parsed[2])})

    def do_write(self, inp):
        '''Write current configuration to either a specified file or to default location
        Syntax: write [file_name]'''
        if inp:
            file = open(inp, "w+")
            file.write(json.dumps(self.config, indent = 4))
        else:
            file = open(self.config_loc, "w+")
            file.write(json.dumps(self.config, indent = 4))

    def do_load(self, inp):
        '''Load configuration from a specified file
        Syntax: load file_name'''
        file = open(inp, "r")
        self.config = json.load(file)

    def do_run(self, inp):
        '''Run DClab using currently saved configuration file'''
        os.system("/opt/onos/bin/onos-app 127.0.0.1 uninstall org.onosproject.dclab")
        os.system("/opt/onos/bin/onos-app 127.0.0.1 reinstall! ~/dcnet-source/dclab/target/onos-app-dclab-2.1.0.oar ")

    def do_apply(self, inp):
        '''Write current configuration to default location, then run DClab'''
        self.do_write(None)
        self.do_run(None)

    def do_show(self, inp):
        '''Shows current state of the topology configuration'''
        print(json.dumps(self.config, indent = 4))

shell = DClabShell
file = open(shell.config_loc, "r")
shell.config = json.load(file)
DClabShell().cmdloop(shell)