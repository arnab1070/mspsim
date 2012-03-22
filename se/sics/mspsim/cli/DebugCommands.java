/**
 * Copyright (c) 2008, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of MSPSim.
 *
 * -----------------------------------------------------------------
 *
 * Author  : Joakim Eriksson, Niclas Finne
 * Created : Mon Feb 11 2008
 */
package se.sics.mspsim.cli;
import se.sics.mspsim.core.CPUMonitor;
import se.sics.mspsim.core.DbgInstruction;
import se.sics.mspsim.core.DisAsm;
import se.sics.mspsim.core.EmulationException;
import se.sics.mspsim.core.Loggable;
import se.sics.mspsim.core.MSP430;
import se.sics.mspsim.core.MSP430Constants;
import se.sics.mspsim.chip.Memory;
import se.sics.mspsim.platform.GenericNode;
import se.sics.mspsim.util.ComponentRegistry;
import se.sics.mspsim.util.DebugInfo;
import se.sics.mspsim.util.ELF;
import se.sics.mspsim.util.GDBStubs;
import se.sics.mspsim.util.MapEntry;
import se.sics.mspsim.util.Utils;

public class DebugCommands implements CommandBundle {
  private long lastCall = 0;
  private long lastWall = 0;
  private ComponentRegistry registry;

  private ELF getELF() {
    return registry.getComponent(ELF.class);
  }

  public void setupCommands(ComponentRegistry registry, CommandHandler ch) {
    this.registry = registry;
    final MSP430 cpu = registry.getComponent(MSP430.class);
    final GenericNode node = (GenericNode) registry.getComponent("node");
    if (cpu != null) {
      ch.registerCommand("break", new BasicAsyncCommand("add a breakpoint to a given address or symbol",
          "<address or symbol>") {
        private int address;
        private CPUMonitor monitor;
        public int executeCommand(final CommandContext context) {
          address = context.getArgumentAsAddress(0);
          if (address < 0) {
            context.err.println("unknown symbol: " + context.getArgument(0));
            return 1;
          }
          monitor = new CPUMonitor() {
              public void notifyReadBefore(int type, int adr, int data) {
                  context.out.println("*** Break at $" + cpu.getAddressAsString(adr));
                  cpu.stop();
              }
              public void notifyReadAfter(int addr, int mode, int type) {
              }
              public void notifyWriteBefore(int dstAddress, int data, int mode) {
              }
              public void notifyWriteAfter(int dstAddress, int data, int mode) {
              }
          };
          cpu.addWatchPoint(address, monitor);
          context.err.println("Breakpoint set at $" + cpu.getAddressAsString(address));
          return 0;
        }
        public void stopCommand(CommandContext context) {
          cpu.removeWatchPoint(address, monitor);
        }
      });

      ch.registerCommand("watch",
          new BasicAsyncCommand("add a write/read watch to a given address or symbol", "<address or symbol> [length] [char | hex | break]") {
        int mode = 0;
        int address = 0;
        int length = 1;
        CPUMonitor monitor;
        public int executeCommand(final CommandContext context) {
          address = context.getArgumentAsAddress(0);
          if (address < 0) {
            context.err.println("unknown symbol: " + context.getArgument(0));
            return -1;
          }
          if (context.getArgumentCount() > 1) {
              for (int i = 1; i < context.getArgumentCount(); i++) {
                  String modeStr = context.getArgument(i);
                  if (Character.isDigit(modeStr.charAt(0))) {
                      length = Integer.parseInt(modeStr);
                  } else if ("char".equals(modeStr)) {
                      mode = Utils.ASCII_UNMODIFIED; // 4
                  } else if ("break".equals(modeStr)) {
                      mode = 10;
                  } else if ("hex".equals(modeStr)) {
                      mode = Utils.HEX; // 2
                  }
              }
          }
          if (length < 1) {
              context.err.println("please specify a length of at least one byte");
              return -1;
          }
          monitor = new CPUMonitor() {
              public void cpuAction(int type, int adr, int data) {
                  if (mode == 0 || mode == 10) {
                      int pc = cpu.getPC();
                      String adrStr = getSymOrAddr(cpu, context, adr);
                      String pcStr = getSymOrAddrELF(cpu, getELF(), pc);
                      String op = "op";
                      if (type == MEMORY_READ) {
                          op = "Read";
                      } else if (type == MEMORY_WRITE){
                          op = "Write";
                      }
                      context.out.println("*** " + op + " from " + pcStr +
                              ": " + adrStr + " = " + data);
                      if (mode == 10) {
                          cpu.stop();
                      }
                  } else {
                      if (length > 1) {
                          for (int i = address; i < address + length; i++) {
                              context.out.print(Utils.toString(cpu.memory[i], Utils.BYTE, mode));
                          }
                          context.out.println();
                      } else {
                          context.out.print(Utils.toString(data, Utils.BYTE, mode));
                      }
                  }
              }

            public void notifyReadBefore(int addr, int mode, int type) {
                cpuAction(CPUMonitor.MEMORY_READ, addr, 0);
            }
            public void notifyReadAfter(int addr, int mode, int type) {
            }
            public void notifyWriteBefore(int dstAddress, int data, int mode) {
                cpuAction(CPUMonitor.MEMORY_WRITE, dstAddress, data);
            }
            public void notifyWriteAfter(int dstAddress, int data, int mode) {
            }
          };

          for (int i = 0; i < length; i++) {
              cpu.addWatchPoint(address + i, monitor);
          }
          if (length > 1) {
              context.err.println("Watch set at $" + cpu.getAddressAsString(address) + " - $" + cpu.getAddressAsString(address + length - 1));
          } else {
              context.err.println("Watch set at $" + cpu.getAddressAsString(address));
          }
          return 0;
        }

        public void stopCommand(CommandContext context) {
            for (int i = 0; i < length; i++) {
                cpu.removeWatchPoint(address + i, monitor);
            }
            context.exit(0);
        }
      });

      ch.registerCommand("watchreg",
          new BasicAsyncCommand("add a write watch to a given register", "<register> [int]") {
        int mode = 0;
        int register = 0;
        CPUMonitor monitor;
        public int executeCommand(final CommandContext context) {
          register = context.getArgumentAsRegister(0);
          if (register < 0) {
            return -1;
          }
          if (context.getArgumentCount() > 1) {
            String modeStr = context.getArgument(1);
            if ("int".equals(modeStr)) {
              mode = 1;
            } else {
              context.err.println("illegal argument: " + modeStr);
              return -1;
            }
          }
          monitor = new CPUMonitor() {
            public void cpuAction(int type, int adr, int data) {
              if (mode == 0) {
                int pc = cpu.getPC();
                String adrStr = getRegisterName(register);
                String pcStr = getSymOrAddrELF(cpu, getELF(), pc);
                context.out.println("*** Write from " + pcStr +
                    ": " + adrStr + " = " + data);
              } else {
                context.out.println(data);
              }
            }
            public void notifyReadBefore(int addr, int mode, int type) {
                cpuAction(CPUMonitor.MEMORY_READ, addr, 0);
            }
            public void notifyReadAfter(int addr, int mode, int type) {
            }
            public void notifyWriteBefore(int dstAddress, int data, int mode) {
                cpuAction(CPUMonitor.MEMORY_WRITE, dstAddress, data);
            }
            public void notifyWriteAfter(int dstAddress, int data, int mode) {
            }
          };
          cpu.addRegisterWriteMonitor(register, monitor);
          context.err.println("Watch set for register " + getRegisterName(register));
          return 0;
        }

        public void stopCommand(CommandContext context) {
          cpu.removeRegisterWriteMonitor(register, monitor);
        }
      });

//      ch.registerCommand("clear", new BasicCommand("clear a breakpoint or watch from a given address or symbol", "<address or symbol>") {
//        public int executeCommand(final CommandContext context) {
//          int baddr = context.getArgumentAsAddress(0);
//          cpu.setBreakPoint(baddr, null);
//          return 0;
//        }
//      });

      ch.registerCommand("symbol", new BasicCommand("list matching symbols", "<regexp>") {
        public int executeCommand(final CommandContext context) {
          String regExp = context.getArgument(0);
          MapEntry[] entries = context.getMapTable().getEntries(regExp);
          if (entries.length == 0) {
              context.err.println("Could not find any symbols matching '" + regExp + '\'');
          } else {
              for (MapEntry mapEntry : entries) {
                  int address = mapEntry.getAddress();
                  context.out.println(" " + mapEntry.getName() + " at $" +
                          cpu.getAddressAsString(address) + " (" + Utils.hex8(cpu.memory[address]) +
                          " " + Utils.hex8(cpu.memory[address + 1]) + ") " + mapEntry.getType() +
                          " in file " + mapEntry.getFile());
              }
          }
          return 0;
        }
      });

      ch.registerCommand("debug", new BasicCommand("set debug to on or off", "[0/1]") {
          public int executeCommand(final CommandContext context) {
              if (context.getArgumentCount() > 0) {
                  cpu.setDebug(context.getArgumentAsBoolean(0));
              }
              context.out.println("Debug is set to " + cpu.getDebug());
              return 0;
          }
      });

      ch.registerCommand("line", new BasicCommand("print line number of address/symbol", "<address or symbol>") {
        public int executeCommand(final CommandContext context) {
          int adr = context.getArgumentAsAddress(0);
          DebugInfo di = getELF().getDebugInfo(adr);
          if (di == null) {
            /* quick hack to test next address too... - since something seems to be off by one sometimes... */
            di = getELF().getDebugInfo(adr + 1);
          }
          if (di != null) {
            di.getLine();
            context.out.println(di);
          } else {
            context.err.println("No line number found for: " + context.getArgument(0));
          }
          return 0;
        }
      });

      if (node != null) {
        ch.registerCommand("stop", new BasicCommand("stop the CPU", "") {
          public int executeCommand(CommandContext context) {
            node.stop();
            context.out.println("CPU stopped at: $" + cpu.getAddressAsString(cpu.getPC()));
            return 0;
          }
        });
        ch.registerCommand("start", new BasicCommand("start the CPU", "") {
          public int executeCommand(CommandContext context) {
            node.start();
            return 0;
          }
        });
        ch.registerCommand("throw", new BasicCommand("throw an Emulation Exception", "") {
            public int executeCommand(CommandContext context) {
                throw new EmulationException(context.getArgument(0));
            }
          });

        ch.registerCommand("step", new BasicCommand("single step the CPU", "[number of instructions]") {
          public int executeCommand(CommandContext context) {
            int nr = context.getArgumentCount() > 0 ? context.getArgumentAsInt(0) : 1;
            long cyc = cpu.cycles;
            if (cpu.isRunning()) {
                context.err.println("Can not single step when emulation is running.");
                return -1;
            }
            try {
              node.step(nr);
            } catch (Exception e) {
              e.printStackTrace(context.out);
            }
            context.out.println("CPU stepped to: $" + cpu.getAddressAsString(cpu.getPC()) +
                " in " + (cpu.cycles - cyc) + " cycles (" + cpu.cycles + ")");
            return 0;
          }
        });

        ch.registerCommand("stepmicro", new BasicCommand("single the CPU specified no micros", "<micro skip> <micro step>") {
          public int executeCommand(CommandContext context) {
            long cyc = cpu.cycles;
            if (cpu.isRunning()) {
                context.err.println("Can not single step when emulation is running.");
                return -1;
            }
            long nxt = 0;
            try {
              nxt = cpu.stepMicros(context.getArgumentAsLong(0), context.getArgumentAsLong(1));
            } catch (Exception e) {
              e.printStackTrace(context.out);
            }
            context.out.println("CPU stepped to: $" + cpu.getAddressAsString(cpu.getPC()) +
                " in " + (cpu.cycles - cyc) + " cycles (" + cpu.cycles + ") - next exec time: " + nxt);
            return 0;
          }
        });

        ch.registerCommand("stack", new BasicCommand("show stack info", "") {
          public int executeCommand(CommandContext context) {
            int stackEnd = context.getMapTable().heapStartAddress;
            int stackStart = context.getMapTable().stackStartAddress;
            int current = cpu.getSP();
            context.out.println("Current stack: $" + cpu.getAddressAsString(current) + " (" + (stackStart - current) + " used of " + (stackStart - stackEnd) + ')');
            return 0;
          }
        });
        ch.registerCommand("print", new BasicCommand("print value of an address or symbol", "<address or symbol>") {
          public int executeCommand(CommandContext context) {
            int adr = context.getArgumentAsAddress(0);
            if (adr >= 0) {
              try {
                context.out.println(context.getArgument(0) + " = $" +
                        Utils.hex16(cpu.currentSegment.read(adr, adr >= 0x100 ? MSP430Constants.MODE_WORD : MSP430Constants.MODE_BYTE, 0)));
              } catch (Exception e) {
                e.printStackTrace(context.err);
              }
              return 0;
            }
            context.err.println("unknown symbol: " + context.getArgument(0));
            return 1;
          }
        });
        ch.registerCommand("printreg", new BasicCommand("print value of an register", "<register>") {
          public int executeCommand(CommandContext context) {
            int register = context.getArgumentAsRegister(0);
            if (register >= 0) {
              context.out.println(context.getArgument(0) + " = $" + Utils.hex16(cpu.getRegister(register)));
              return 0;
            }
            return -1;
          }
        });
        ch.registerCommand("reset", new BasicCommand("reset the CPU", "") {
          public int executeCommand(CommandContext context) {
            cpu.reset();
            return 0;
          }
        });

        ch.registerCommand("time", new BasicCommand("print the elapse time and cycles", "") {
          public int executeCommand(CommandContext context) {
            long time = ((long)(cpu.getTimeMillis()));
	    long wallDiff = System.currentTimeMillis() - lastWall;
            context.out.println("Emulated time elapsed: " + time + "(ms)  since last: " + (time - lastCall) + " ms" + " wallTime: " +
				wallDiff + " ms speed factor: " +
				(wallDiff == 0 ? "N/A" : "" + (time - lastCall) / wallDiff));
            lastCall = time;
            lastWall = System.currentTimeMillis();
            return 0;
          }
        });

        ch.registerCommand("mem", new BasicCommand("dump memory", "<start address> <num_entries> [type] [hex|char|dis]") {
          public int executeCommand(final CommandContext context) {
            int start = context.getArgumentAsAddress(0);
            if (start < 0) {
              context.err.println("Illegal start address: "
                                  + context.getArgument(0));
              return 1;
            }
            int count = context.getArgumentAsInt(1);
            int mode = Utils.DEC;
            int type = Utils.UBYTE;
            boolean signed = false;
            if (context.getArgumentCount() > 2) {
                int pos = 2;
                int acount = context.getArgumentCount();
                if (acount > 4) acount = 4;
                while (pos < acount) {
                    String tS = context.getArgument(pos++);
                    if ("ubyte".equals(tS)) {
                    } else if ("byte".equals(tS)) {
                        type = Utils.BYTE;
                    } else if ("word".equals(tS)) {
                        type = Utils.WORD;
                    } else if ("uword".equals(tS)) {
                        type = Utils.UWORD;
                    } else if ("hex".equals(tS)) {
                        mode = Utils.HEX;
                    } else if ("char".equals(tS)) {
                        mode = Utils.ASCII;
                        type = Utils.BYTE;
                    } else if ("dis".equals(tS)) {
                        mode = Utils.DIS_ASM;
                        type = Utils.WORD;
                    }
                }
            }
            // Does not yet handle signed data...
            DisAsm disAsm = cpu.getDisAsm();
            for (int i = 0; i < count; i++) {
                if (mode == Utils.DIS_ASM) {
                    DbgInstruction dbg = disAsm.disassemble(start, cpu.memory, cpu.reg, new DbgInstruction(),
                            0);
                    String fkn;
                    if ((fkn = dbg.getFunction()) != null) {
                        context.out.println("//// " + fkn);
                    }
                    context.out.println(dbg.getASMLine(false));
                    start += dbg.getSize();
                } else {
                    int data = 0;
                    data = cpu.memory[start++];
                    if (Utils.size(type) == 2) {
                        data = data  + (cpu.memory[start++] << 8);
                    }
                    context.out.print((mode != Utils.ASCII ? " " : "") + 
                            Utils.toString(data, type, mode));
                }
            }
            context.out.println();
            return 0;
          }
        });

        ch.registerCommand("mset", new BasicCommand("set memory", "<address> [type] <value> [value ...]") {
          public int executeCommand(final CommandContext context) {
            int count = context.getArgumentCount();
            int adr = context.getArgumentAsAddress(0);
            String arg2 = context.getArgument(1);
            int type = Utils.BYTE;
            int mode = Utils.DEC;
            boolean typeRead = false;
            if (count > 2) {
              if ("char".equals(arg2)) {
                mode = Utils.ASCII;
                typeRead = true;
              }
              if ("word".equals(arg2)) {
                type = Utils.WORD;
                typeRead = true;
              }
            }
            for (int i = typeRead ? 2 : 1; i < count; i++) {
              if (mode == Utils.DEC) {
                int val = context.getArgumentAsInt(i);
                boolean word = Utils.size(type) == 2 | val > 0xff;
                try {
                  cpu.currentSegment.write(adr, val, word ? MSP430Constants.MODE_WORD : MSP430Constants.MODE_BYTE);
                  adr += word ? 2 : 1;
                } catch (EmulationException e) {
                  e.printStackTrace(context.out);
                }
              } else if (mode == Utils.ASCII) {
                String data = context.getArgument(i);
                for (int j = 0; j < data.length(); j++) {
                  cpu.currentSegment.write(adr++, data.charAt(j) & 0xff, MSP430Constants.MODE_WORD);
                }
              }
            }
            return 0;
          }});

        /******************************************************
         * handle external memory (flash, etc).
         ******************************************************/
        ch.registerCommand("xmem", new BasicCommand("dump flash memory", "<start address> <num_entries> [type]") {
          public int executeCommand(final CommandContext context) {
            Memory xmem = (Memory) DebugCommands.this.registry.getComponent("xmem");
            if (xmem == null) {
              context.err.println("No xmem component registered");
              return 0;
            }
            int start = context.getArgumentAsAddress(0);
            int count = context.getArgumentAsInt(1);
            int size = 1; // unsigned byte
            boolean signed = false;
            if (context.getArgumentCount() > 2) {
              String tS = context.getArgument(2);
              if ("byte".equals(tS)) {
                signed = true;
              } else if ("word".equals(tS)) {
                signed = true;
                size = 2;
              } else if ("uword".equals(tS)) {
                size = 2;
              }
            }
            // Does not yet handle signed data...
            for (int i = 0; i < count; i++) {
              int data = 0;
              data = xmem.readByte(start++);
              if (size == 2) {
                data = data  + (xmem.readByte(start++) << 8);
              }
              context.out.print(" " + data);
            }
            context.out.println();
            return 0;
          }
        });

        ch.registerCommand("xmset", new BasicCommand("set memory", "<address> <value> [type]") {
          public int executeCommand(final CommandContext context) {
            Memory xmem = (Memory) DebugCommands.this.registry.getComponent("xmem");
            if (xmem == null) {
              context.err.println("No xmem component registered");
              return 0;
            }
            int adr = context.getArgumentAsAddress(0);
            int val = context.getArgumentAsInt(1);
            boolean word = val > 0xff;
            if (word) {
              xmem.writeByte(adr, val >> 8);
              val = val & 0xff;
              adr++;
            }
            xmem.writeByte(adr, val & 0xff);
            return 0;
          }});

        ch.registerCommand("gdbstubs", new BasicCommand("open up a gdb stubs server for GDB remote debugging", "port") {
          private GDBStubs stubs = null;
          public int executeCommand(CommandContext context) {
            if (stubs != null) {
              context.err.println("GDBStubs already open");
            } else {
              int port = context.getArgumentAsInt(0);
              stubs = new GDBStubs();
              stubs.setupServer(cpu, port);
            }
            return 0;
          }
        });

        ch.registerCommand("log", new BasicAsyncCommand("log a loggable object", "[loggable...]" ) {
            private Loggable[] loggables = null;

            @Override
            public int executeCommand(CommandContext context) {
                if (context.getArgumentCount() == 0) {
                    Loggable[] loggable = cpu.getLoggables();
                    for (Loggable unit : loggable) {
                        String id = unit.getID();
                        String name = unit.getName();
                        if (id == name) {
                            context.out.println("  " + id);
                        } else {
                            context.out.println("  " + id + " (" + name + ')');
                        }
                    }
                    context.exit(0);
                    return 0;
                }

                Loggable[] logs = new Loggable[context.getArgumentCount()];
                for(int i = 0, n = context.getArgumentCount(); i < n; i++) {
                    logs[i] = cpu.getLoggable(context.getArgument(i));
                    if (logs[i] == null) {
                        context.err.println("Can not find loggable '" + context.getArgument(i) + '\'');
                        return 1;
                    }
                }
                for(Loggable l : logs) {
                    l.setLogStream(context.out);
                }
                this.loggables = logs;
                return 0;
            }

            public void stopCommand(CommandContext context) {
                if (loggables != null) {
                    for(Loggable l : loggables) {
                        l.clearLogStream();
                    }
                }
            }
        });

        ch.registerCommand("trace", new BasicCommand("store a trace of execution positions.", "<trace size | show>") {
            @Override
            public int executeCommand(CommandContext context) {
        	if ("show".equals(context.getArgument(0))) {
        	    int size = cpu.getTraceSize();
                    if (size == 0) {
                        context.err.println("trace size is set to 0");
                    } else {
                        DisAsm disAsm = cpu.getDisAsm();
                        for (int i = 0; i < size; i++) {
                            int pc = cpu.getBackTrace(size - 1 - i);
                            DbgInstruction inst = disAsm.getDbgInstruction(pc, cpu);
                            inst.setPos(pc);
                            context.out.println(inst);
                        }
                    }
        	} else {
        	    cpu.setTrace(context.getArgumentAsInt(0));
        	}
              return 0;
            }
          });        

        ch.registerCommand("events", new BasicCommand("print event queues", "") {
            @Override
            public int executeCommand(CommandContext context) {
                cpu.printEventQueues(context.out);
              return 0;
            }
          });
      }
    }
  }

  private static String getSymOrAddr(MSP430 cpu, CommandContext context, int adr) {
    MapEntry me = context.getMapTable().getEntry(adr);
    if (me != null) {
      return me.getName();
    }
    return '$' + cpu.getAddressAsString(adr);
  }

  private static String getSymOrAddrELF(MSP430 cpu, ELF elf, int adr) {
    DebugInfo me = elf.getDebugInfo(adr);
    if (me != null) {
      return me.toString();
    }
    return '$' + cpu.getAddressAsString(adr);
  }

  private static String getRegisterName(int register) {
    if (register >= 0 && register < MSP430Constants.REGISTER_NAMES.length) {
      return MSP430Constants.REGISTER_NAMES[register];
    }
    return "R" + register;
  }
}
