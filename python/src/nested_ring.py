from mininet.net import Mininet
from mininet.topo import Topo
from mininet.cli import CLI
from mininet.log import setLogLevel
from mininet.node import RemoteController, Node, Host, OVSKernelSwitch
from mininet.link import TCLink

from mininet.link import TCLink
from argparse import ArgumentParser
import traceback
import random
import time
import json
import math


# Function to parse the command line arguments
def parseOptions():
    size = 23
    hops = [1, 5]
    fanout = 2

    parser = ArgumentParser("Create a folded Clos network topology")

    # Add arguments to the parser for leaf, spine, pod, super spine, and fanout options
    parser.add_argument("--size", type=int,
                        help="Number of switches in each ring")
    parser.add_argument("--hops", type=int, nargs="+",
                        help="List of hop lengths within ring")
    parser.add_argument("--fanout", type=int,
                        help="Number of hosts per edge switch")

    args = parser.parse_args()

    # Change the values if passed on command line
    if args.size:
        size = args.size
    if args.hops:
        hops = args.hops
    if args.fanout:
        fanout = args.fanout

    return size, hops, fanout


# Class defining a Folded Clos topology using super spines
class NestedRing(Topo):
    def __init__(self, size, hops, fanout):
        "Create Nested Ring Topo."

        Topo.__init__(self)

        # Simple counter for assigning host names
        host_count = 1
        switch_count = 1;

        # Counter with adjustable increments for switch names. Choose
        # increment and initial values to easily identify switch types
        increment = 16
        core_count = 10 + increment
        edge_count = 11 + increment

        core_switches = []
        edge_switches = []

        switch_config = []

        for c in range(size):
            core_name = "c" + str(core_count)
            ip_addr = "10.0.1" + format(switch_count >> 8, "02d") + "."
            ip_addr += format(switch_count & 0xFF, "d") + "/12"
            self.addSwitch(core_name, ip=ip_addr)
            switch_count += 1
            core_switches.append(core_name)
            switch_config.append({"id": format(core_count, "x"),
                                  "name": core_name,
                                  "latitude": math.sin((2 * c * math.pi) / size),
                                  "longitude": math.cos((2 * c * math.pi) / size) - 1.5})
            core_count += increment

        for e in range(size):
            edge_name = "e" + str(edge_count)
            ip_addr = "10.0.1" + format(switch_count >> 8, "02d") + "."
            ip_addr += format(switch_count & 0xFF, "d") + "/12"
            self.addSwitch(edge_name, ip=ip_addr)
            switch_count += 1
            edge_switches.append(edge_name)
            switch_config.append({"id": format(edge_count, "x"),
                                  "name": edge_name,
                                  "latitude": math.sin((2 * e * math.pi) / size),
                                  "longitude": math.cos((2 * e * math.pi) / size) + 1.5})
            edge_count += increment

            for f in range(fanout):
                host_name = "h" + str(host_count)
                # Construct host IPv4 address, first 8 bits are reserved,
                # last 24 bits uniquely identify a host
                ip_addr = "10.0." + format(host_count >> 8, "d") + "."
                ip_addr += format(host_count & 0xFF, "d")

                # Construct host UID MAC address, first 24 bits are reserved,
                # last 24 bits uniquely identify a host
                mac_addr = "dc:dc:dc:" + format((host_count >> 16) & 0xFF, "02x")
                mac_addr += ":" + format((host_count >> 8) & 0xFF, "02x")
                mac_addr += ":" + format(host_count & 0xFF, "02x")

                self.addHost(host_name, ip=ip_addr + "/12", mac=mac_addr)
                host_count += 1
                self.addLink(edge_switches[e], host_name,
                             cls=TCLink, bw=10, delay="1ms")

        config = open("../../config/mininet/switch_config.json", "w+")
        config.write(json.dumps(switch_config, indent = 4))

        cl = len(core_switches)
        el = len(edge_switches)
        for h in range(len(hops)):
            for c in range(cl):
                self.addLink(core_switches[c], core_switches[(c + hops[h]) % cl],
                             cls=TCLink, bw=10, delay="1ms")

        for e in range(el):
            self.addLink(edge_switches[e], edge_switches[(e + 1) % el],
                         cls=TCLink, bw=10, delay="1ms")
            self.addLink(edge_switches[e], core_switches[e],
                         cls=TCLink, bw=10, delay="1ms")


if __name__ == "__main__":
    net = None
    try:
        setLogLevel("info")
        size, hops, fanout = parseOptions()
        topo = NestedRing(size, hops, fanout)
        net = Mininet(topo, controller=RemoteController, link=TCLink)
        net.start()

        CLI(net)
    finally:
        if net is not None:
            net.stop()

topos = {'NestedRing':
             (lambda size, hops, fanout:
              NestedRing(size, hops, fanout))}
