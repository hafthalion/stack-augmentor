package com.hafnium.it.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Runs the application's executable jar, as built and packaged by Spring Boot, in a JVM of its own. */
class SpringBootTest {

    private static final String SHIP = "\tat com.hafnium.it.spring.Shipping<eu>.ship{orderId: o-1}(Shipping.java:";

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
    void buildTimeInstrumentationUsesTheConfigurationInTheJar() throws Exception {
        String output = run();
        assertTrue(output.contains(SHIP), output);
        // Libraries are not instrumented at build time.
        assertTrue(output.contains("\tat org.springframework.util.Assert.notNull(Assert.java:"), output);
        assertTrue(!output.contains("[stack-augmentor] WARN"), output);
    }

    @Test
    void theAgentUsesTheConfigurationInTheJarAfterItsOwn() throws Exception {
        String output = run("-javaagent:" + System.getProperty("stackaugmentor.it.agentJar") + "=config="
                + System.getProperty("stackaugmentor.it.agentConfig"));
        // The build-time instrumented class, with the format of the agent's configuration, which applies first.
        assertTrue(output.contains("\tat com.hafnium.it.spring.Shipping[region: eu].ship{orderId: o-1}(Shipping.java:"), output);
        // The library class that the application's configuration selects: the agent instruments it.
        assertTrue(output.contains("\tat org.springframework.util.Assert.notNull{message=order o-1 has no address}(Assert.java:"), output);
        assertTrue(!output.contains("[stack-augmentor] WARN"), output);
    }
}
