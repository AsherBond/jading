package com.etsy.jading;

import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

import org.jruby.Ruby;
import org.jruby.RubyHash;
import org.jruby.RubyInstanceConfig;
import org.jruby.runtime.GlobalVariable;

/**
 * Executable class wrapping either a cascading.jruby script or a class that
 * implements CascadingRunnable. This allows us to run cascading.jruby scripts
 * or Java classes packaged in a jar.
 * 
 * @see CascadingRunnable
 */
public class Main {
    private static Logger LOG = Logger.getLogger(Main.class.getName());

    /**
     * Starts a Cascading job by either executing the specified JRuby script or
     * loading and running an instance of the specified CascadingRunnable class.
     * 
     * @param args Array of string input arguments. The first is either the path
     *            to a cascading.jruby script (which must end in ".rb") or the
     *            fully qualified class name of the CascadingRunnable you would
     *            like to execute. Arguments following the first are forwarded
     *            to the job being run.
     */
    public static void main(String[] args) throws ClassNotFoundException, IllegalAccessException,
            InstantiationException, IOException {
        run(new Properties(), args);
    }

    public static void run(Properties props, String[] args) throws ClassNotFoundException, IllegalAccessException, InstantiationException, IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: com.etsy.jading.Main <script path or class name> [args]");
        }

        StringBuilder logProperties = new StringBuilder();
        logProperties.append("Main.run() properties:");
        for (Object key : props.keySet()) {
            logProperties.append("\n" + key + "=" + props.get(key));
        }
        LOG.info(logProperties.toString());

        if (args[0].endsWith(".rb")) {
            String cascadingScript = args[0]; // cascading.jruby script path

            // Construct cascading.jruby script args
            String[] cascadingScriptArgs = new String[args.length];
            System.arraycopy(args, 0, cascadingScriptArgs, 0, args.length);

            // Replace the path to the cascading.jruby script with an artificial
            // "script" name for what is being executed in the JRuby runtime by
            // this class. This name is never used since we directly call
            // executeScript upon the runtime.
            cascadingScriptArgs[0] = "com/etsy/jading/Main.java";

            RubyInstanceConfig config = new RubyInstanceConfig();
            config.processArguments(cascadingScriptArgs);

            StringBuilder logArgs = new StringBuilder();
            logArgs.append("cascading.jruby script ARGV:");
            for (String arg : config.getArgv()) {
                logArgs.append("\n" + arg);
            }
            LOG.info(logArgs.toString());

            Ruby runtime = Ruby.newInstance(config);

            /*
             * See long comment in lib/cascading/cascading.rb about why this is
             * an unsatisfying approach to configuring user jobs.  A better way
             * of doing this would be to instantiate a job defined by the user
             * in the runner and pass it properties at that time, but this
             * would require a new piece that doesn't exist, yet.
             */
            RubyHash rubyProperties = RubyHash.newHash(runtime);
            rubyProperties.putAll(props);
            runtime.defineVariable(new GlobalVariable(runtime, "$jobconf_properties", rubyProperties));

            LOG.info("Requiring 'com/etsy/jading/gem_path'");
            runtime.executeScript("require 'com/etsy/jading/gem_path'", "com/etsy/jading/gem_path.rb");

            LOG.info("Requiring '" + cascadingScript + "'");
            runtime.executeScript("require '" + cascadingScript + "'", cascadingScript);

            LOG.info("Requiring 'com/etsy/jading/runner'");
            runtime.executeScript("require 'com/etsy/jading/runner'", "com/etsy/jading/runner.rb");
        } else {
            String className = args[0];
            LOG.info("Received non-Ruby path; attempting to run CascadingRunnable: '" + className + "'");

            Class<?> c = Class.forName(className);
            if (CascadingRunnable.class.isAssignableFrom(c)) {
                String[] cascadingRunnableArgs = new String[args.length - 1];
                System.arraycopy(args, 1, cascadingRunnableArgs, 0, args.length - 1);

                StringBuilder logArgs = new StringBuilder();
                logArgs.append("CascadingRunnable args:");
                for (String arg : cascadingRunnableArgs) {
                    logArgs.append("\n" + arg);
                }
                LOG.info(logArgs.toString());

                CascadingRunnable cascadingRunnable = (CascadingRunnable) c.newInstance();
                cascadingRunnable.prepare(className, cascadingRunnableArgs, props);
                cascadingRunnable.run();
            } else {
                throw new IllegalArgumentException("Class is not a CascadingRunnable: '" + className + "'");
            }
        }
    }
}
