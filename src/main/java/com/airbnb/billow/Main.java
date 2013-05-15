package com.airbnb.billow;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.jetty9.InstrumentedHandler;
import com.codahale.metrics.servlets.AdminServlet;
import com.codahale.metrics.servlets.HealthCheckServlet;
import com.codahale.metrics.servlets.MetricsServlet;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.ServletContext;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.io.Resources.getResource;

@Slf4j
public class Main {
    private Main(Config config) throws Exception {
        log.info("Startup...");

        try {
            System.out.println(Resources.toString(getResource("banner.txt"), Charsets.UTF_8));
        } catch (IllegalArgumentException | IOException e) {
            log.debug("No banner.txt", e);
        }

        final MetricRegistry metricRegistry = new MetricRegistry();
        final HealthCheckRegistry healthCheckRegistry = new HealthCheckRegistry();

        log.info("Creating database");

        final Config awsConfig = config.getConfig("aws");
        final Long refreshRate = awsConfig.getMilliseconds("refreshRate");
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        final AWSDatabaseHolder ec2DatabaseHolder = new AWSDatabaseHolder(awsConfig);
        final ScheduledFuture<?> updates = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                ec2DatabaseHolder.rebuild();
            }
        }, refreshRate, refreshRate, TimeUnit.MILLISECONDS);

        log.info("Creating HTTP servers");
        final Server mainServer = new Server(config.getInt("mainPort"));
        final Server adminServer = new Server(config.getInt("adminPort"));

        configureConnectors(mainServer);
        configureConnectors(adminServer);

        log.info("Creating HTTP handlers");
        final Handler mainHandler = new Handler(metricRegistry, ec2DatabaseHolder);
        final InstrumentedHandler instrumentedHandler =
                new InstrumentedHandler(metricRegistry);
        instrumentedHandler.setHandler(mainHandler);

        mainServer.setHandler(instrumentedHandler);

        final ServletContextHandler adminHandler = new ServletContextHandler();
        adminHandler.addServlet(new ServletHolder(new AdminServlet()), "/*");

        final ServletContext adminContext = adminHandler.getServletContext();
        adminContext.setAttribute(MetricsServlet.METRICS_REGISTRY, metricRegistry);
        adminContext.setAttribute(HealthCheckServlet.HEALTH_CHECK_REGISTRY, healthCheckRegistry);
        adminServer.setHandler(adminHandler);

        log.info("Creating health check");

        log.info("Starting HTTP servers");

        adminServer.start();
        mainServer.start();

        log.info("Joining...");

        mainServer.join();
        adminServer.join();

        log.info("We're done!");
    }

    public static void main(String[] args) {
        try {
            final Config config = ConfigFactory.load().getConfig("billow");
            Main.log.debug("Loaded config: {}", config);
            new Main(config);
        } catch (Throwable t) {
            Main.log.error("Failure in main thread, getting out!", t);
            System.exit(1);
        }
    }

    private static void configureConnectors(Server server) {
        for (Connector c : server.getConnectors()) {
            for (ConnectionFactory f : c.getConnectionFactories())
                if (f instanceof HttpConnectionFactory) {
                    final HttpConfiguration httpConf =
                            ((HttpConnectionFactory) f).getHttpConfiguration();
                    httpConf.setSendServerVersion(false);
                    httpConf.setSendDateHeader(false);
                }
        }
    }
}