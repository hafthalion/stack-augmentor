package com.hafnium.it.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Runs the application as Quarkus packages it (quarkus-app/quarkus-run.jar), in a JVM of its own. */
class QuarkusTest {

    private static final String SHIP = "\tat com.hafnium.it.quarkus.Shipping<us>.ship{orderId=o-2}(Shipping.java:";

    private static String run(String... jvmOptions) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of(jvmOptions));
        command.add("-jar");
        command.add(System.getProperty("stackaugmentor.it.application"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), output);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void buildTimeInstrumentationUsesTheConfigurationInTheApplication() throws Exception {
        String output = run();
        assertTrue(output.contains(SHIP), output);
        // Libraries are not instrumented at build time.
        assertTrue(output.contains("\tat io.smallrye.mutiny.helpers.ParameterValidation.nonNull("), output);
        assertTrue(!output.contains("[stack-augmentor] WARN"), output);
    }

    @Test
    void theAgentUsesTheConfigurationInTheApplicationForItsClasses() throws Exception {
        String output = run("-javaagent:" + System.getProperty("stackaugmentor.it.agentJar") + "=config="
                + System.getProperty("stackaugmentor.it.agentConfig"));
        // The build-time instrumented class, with the format of the application's configuration, not the agent's.
        assertTrue(output.contains(SHIP), output);
        // The library class that the application's configuration selects: the agent instruments it (compiled without
        // parameter names).
        assertTrue(output.contains("\tat io.smallrye.mutiny.helpers.ParameterValidation.nonNull{arg1=address of o-2}("), output);
        // The client proxy is generated (synthetic): never instrumented.
        assertTrue(output.contains("\tat com.hafnium.it.quarkus.Shipping_ClientProxy.ship("), output);
        assertTrue(!output.contains("[stack-augmentor] WARN"), output);
    }
}
