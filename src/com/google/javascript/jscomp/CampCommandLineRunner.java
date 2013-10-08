package com.google.javascript.jscomp;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilerOptions;

public class CampCommandLineRunner extends CommandLineRunner {

  protected CampCommandLineRunner(String[] args) {
    // The superclass is responsible for parsing the command-line arguments.
    super(args);
  }


  @Override
  protected CompilerOptions createOptions() {
    // Let the superclass create the CompilerOptions using the values parsed
    // from the command-line arguments.
    CompilerOptions options = super.createOptions();
    options.exportLocalPropertyDefinitions = true;
    options.removeUnusedPrototypePropertiesInExterns = false;
    return options;
  }


  @Override
  protected Compiler createCompiler() {
    return new CampCompiler(getErrorPrintStream());
  }

  private static List<Closeable> streamList = Lists.newArrayList();


  private static void closeAll() {
    for (Closeable stream : streamList) {
      try {
        stream.close();
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
  }


  private int runWithoutExit() {
    int result = 0;
    int runs = 1;
    try {
      for (int i = 0; i < runs && result == 0; i++) {
        result = doRun();
      }
    } catch (AbstractCommandLineRunner.FlagUsageException e) {
      System.err.println(e.getMessage());
      result = -1;
    } catch (Throwable t) {
      t.printStackTrace();
      result = -2;
    }

    Class<AbstractCommandLineRunner> c = AbstractCommandLineRunner.class;

    Appendable jsOutput;
    try {
      Field f = c.getDeclaredField("jsOutput");
      f.setAccessible(true);
      jsOutput = (Appendable) f.get(this);
    } catch (IllegalArgumentException e) {
      throw new RuntimeException(e);
    } catch (SecurityException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }

    if (jsOutput instanceof Closeable) {
      streamList.add(((Closeable) jsOutput));
    }
    return result;
  }


  private static final class CommandLineOptions {
    @Option(name = "--flagfile", usage = "pass the google closure compiler flag file location.")
    private List<String> flagFiles = Lists.newArrayList();

    @Argument
    private List<String> arguments = Lists.newArrayList();
  }


  /** Runs the Compiler */
  public static void main(String[] args) {
    CommandLineOptions options = new CommandLineOptions();
    CmdLineParser parser = new CmdLineParser(options);
    int result = 0;
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(-1);
    }

    if (options.flagFiles.size() == 0) {
      result = runCompiler(args);
    } else {
      for (String flagFile : options.flagFiles) {
        List<String> clone = Lists.newArrayList(options.arguments);
        clone.add("--flagfile");
        clone.add(flagFile);
        String[] argumentsArray = clone.toArray(new String[options.arguments.size()]);
        result = runCompiler(argumentsArray);
        if (result != 0) {
          break;
        }
        System.gc();
      }
    }
    CampCommandLineRunner.closeAll();
    System.exit(result);
  }


  private static int runCompiler(String[] args) {
    CampCommandLineRunner runner = new CampCommandLineRunner(args);
    if (runner.shouldRunCompiler()) {
      return runner.runWithoutExit();
    } else {
      return -1;
    }
  }
}
