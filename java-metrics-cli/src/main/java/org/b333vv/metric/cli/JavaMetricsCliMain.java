package org.b333vv.metric.cli;

public final class JavaMetricsCliMain {

    private JavaMetricsCliMain() {
    }

    public static void main(String[] args) {
        int exitCode = new JavaMetricsCliApplication().run(args, System.out, System.err);
        System.exit(exitCode);
    }
}
