/*
 * JourneyMap Mod <journeymap.info> for Minecraft
 * Copyright (c) 2011-2017  Techbrew Interactive, LLC <techbrew.net>.  All Rights Reserved.
 */

package journeymap.client.log;

import journeymap.client.Constants;
import journeymap.client.JourneymapClient;
import journeymap.client.feature.FeatureManager;
import journeymap.client.io.FileHandler;
import journeymap.client.properties.PropertiesBase;
import journeymap.client.ui.option.StringListProvider;
import journeymap.common.Journeymap;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.ForgeVersion;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender;
import org.apache.logging.log4j.core.config.BaseConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


public class JMLogger
{
    public static final String DEPRECATED_LOG_FILE = "journeyMap.log"; //$NON-NLS-1$
    public static final String LOG_FILE = "journeymap.log"; //$NON-NLS-1$

    // Singleton error hashcodes
    static private final HashSet<Integer> singletonErrors = new HashSet<Integer>();
    static private final AtomicInteger singletonErrorsCounter = new AtomicInteger(0);

    private static RandomAccessFileAppender fileAppender;

    /**
     * Re-adds the file appender and the configured log level whenever log4j installs a new Configuration
     * (LoggerContext.reconfigure()/setConfigLocation()): the new configuration tree doesn't know about
     * JourneyMap's LoggerConfig or appender, and stopping the previous configuration stopped (and closed)
     * the appender.
     */
    private static final PropertyChangeListener RECONFIGURATION_LISTENER = new PropertyChangeListener()
    {
        @Override
        public void propertyChange(PropertyChangeEvent event)
        {
            if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName()))
            {
                onConfigurationChanged((Configuration) event.getNewValue());
            }
        }
    };

    public static Logger init()
    {
        final Logger logger = LogManager.getLogger(Journeymap.MOD_ID);

        if (!logger.isInfoEnabled())
        {
            logger.warn("Forge is surpressing INFO-level logging. If you need technical support for JourneyMap, you must return logging to INFO.");
        }

        // Remove deprecated logfile
        try
        {
            File deprecatedLog = new File(FileHandler.getJourneyMapDir(), DEPRECATED_LOG_FILE);
            if (deprecatedLog.exists())
            {
                deprecatedLog.delete();
            }
        }
        catch (Exception e)
        {
            logger.error("Error removing deprecated logfile: {}", e.getMessage());
        }

        // File logging
        try
        {
            final File logFile = getLogFile();
            if (logFile.exists())
            {
                logFile.delete();
            }
            else
            {
                logFile.getParentFile().mkdirs();
            }

            fileAppender = createFileAppender(logFile);

            final org.apache.logging.log4j.core.Logger coreLogger = (org.apache.logging.log4j.core.Logger) logger;
            coreLogger.addAppender(fileAppender);
            if (!fileAppender.isStarted())
            {
                fileAppender.start();
            }
            registerReconfigurationListener(coreLogger);

            logger.info("JourneyMap log initialized.");

        }
        catch (SecurityException e)
        {
            logger.error("Error adding file handler: {}", LogFormatter.toString(e));
        }
        catch (Throwable e)
        {
            logger.error("Error adding file handler: {}", LogFormatter.toString(e));
        }

        return logger;
    }

    private static RandomAccessFileAppender createFileAppender(File logFile)
    {
        if (logFile.getParentFile() != null)
        {
            logFile.getParentFile().mkdirs();
        }

        PatternLayout layout = PatternLayout.createLayout(
                "[%d{HH:mm:ss}] [%t/%level] [%C{1}] %msg%n", null,
                null, "UTF-8", "true");

        return RandomAccessFileAppender
                .createAppender(
                        logFile.getAbsolutePath(),// filename
                        "true",// append
                        "journeymap-logfile",// name
                        "true",// immediateFlush
                        "true",// ignoreExceptions
                        layout,
                        null,
                        "false",// advertise
                        null,// advertiseURI
                        null// config
                );
    }

    public static void setLevelFromProperties()
    {
        try
        {
            final Level level = Level.toLevel(JourneymapClient.getCoreProperties().logLevel.get(), Level.INFO);
            final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(Journeymap.MOD_ID);

            // Persist the level in the logger configuration tree as well. Logger.setLevel() alone is only a
            // temporary override on the Logger instance, and is discarded by any third party call to
            // LoggerContext.updateLoggers(), which re-reads the level from the LoggerConfig (in the default
            // Forge setup that is the "journeymap" LoggerConfig created when the file appender was added,
            // with the level inherited from the root logger: ALL).
            final Configuration configuration = logger.getContext().getConfiguration();
            final LoggerConfig loggerConfig = getOrCreateLoggerConfig(configuration, level);
            if (loggerConfig != null)
            {
                loggerConfig.setLevel(level);
            }

            // Apply the level to the existing Logger instance immediately. Do NOT call
            // LoggerContext.updateLoggers() here: that is a global operation and would discard the
            // temporary level overrides of other mods.
            logger.setLevel(level);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    /**
     * Gets the JourneyMap LoggerConfig from the given configuration, creating and registering it first if
     * the configuration tree doesn't contain it yet. Unlike Logger.addAppender(), this does not trigger a
     * global LoggerContext.updateLoggers().
     *
     * @param level level for a newly created LoggerConfig, or null to inherit the parent's level
     * @return the LoggerConfig, or null if it doesn't exist and can't be registered in this configuration
     */
    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration, Level level)
    {
        LoggerConfig loggerConfig = configuration.getLoggerConfig(Journeymap.MOD_ID);
        if (Journeymap.MOD_ID.equals(loggerConfig.getName()))
        {
            return loggerConfig;
        }
        if (!(configuration instanceof BaseConfiguration))
        {
            return null;
        }
        loggerConfig = new LoggerConfig(Journeymap.MOD_ID, level == null ? loggerConfig.getLevel() : level, loggerConfig.isAdditive());
        ((BaseConfiguration) configuration).addLogger(Journeymap.MOD_ID, loggerConfig);
        return loggerConfig;
    }

    private static void registerReconfigurationListener(org.apache.logging.log4j.core.Logger logger)
    {
        try
        {
            final LoggerContext context = logger.getContext();
            // init() can run more than once (canBeDeactivated), don't register the listener twice
            context.removePropertyChangeListener(RECONFIGURATION_LISTENER);
            context.addPropertyChangeListener(RECONFIGURATION_LISTENER);
        }
        catch (Throwable t)
        {
            logger.error("Error registering log reconfiguration listener: {}", LogFormatter.toString(t));
        }
    }

    /**
     * Called when log4j installs a new Configuration (LoggerContext.reconfigure()): rebuilds the JourneyMap
     * LoggerConfig and file appender in the new configuration tree and re-applies the configured level.
     */
    private static void onConfigurationChanged(Configuration configuration)
    {
        try
        {
            if (configuration == null)
            {
                return;
            }
            final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(Journeymap.MOD_ID);
            if (fileAppender != null)
            {
                // The previous configuration's stop() stopped and closed our appender, so create a fresh
                // one (append mode keeps the already written log) and attach it to the new configuration.
                final RandomAccessFileAppender newAppender = createFileAppender(getLogFile());
                if (configuration instanceof BaseConfiguration)
                {
                    ((BaseConfiguration) configuration).addAppender(newAppender);
                }
                final LoggerConfig loggerConfig = getOrCreateLoggerConfig(configuration, null);
                if (loggerConfig != null)
                {
                    loggerConfig.addAppender(newAppender, null, null);
                }
                fileAppender = newAppender;
                if (!fileAppender.isStarted())
                {
                    fileAppender.start();
                }
                // setConfiguration() refreshed all Logger instances before firing this event, i.e. they still
                // reference the LoggerConfig of the new tree that existed at that point (the root logger).
                // Refresh once more so they route through the rebuilt JourneyMap LoggerConfig. This is safe
                // here because reconfigure() is a global operation anyway; the normal logging path never
                // calls updateLoggers().
                logger.getContext().updateLoggers();
            }
            setLevelFromProperties();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    /**
     * Show system properties and those from the PropertyManager.
     */
    public static void logProperties()
    {
        LogEvent record = new Log4jLogEvent(JourneymapClient.MOD_NAME, MarkerManager.getMarker(JourneymapClient.MOD_NAME), null, Level.INFO, new SimpleMessage(getPropertiesSummary()), null);
        if (fileAppender != null)
        {
            fileAppender.append(record);
        }
    }

    /**
     * TODO: Clean up
     */
    public static String getPropertiesSummary()
    {
        LinkedHashMap<String, String> props = new LinkedHashMap<String, String>();

        // Versions
        props.put("Version", JourneymapClient.MOD_NAME + ", built with Forge " + Journeymap.FORGE_VERSION);
        props.put("Forge", ForgeVersion.getVersion());

        // Environment
        List<String> envProps = Arrays.asList("os.name, os.arch, java.version, user.country, user.language");
        StringBuilder sb = new StringBuilder();
        for (String env : envProps)
        {
            sb.append(env).append("=").append(System.getProperty(env)).append(", ");
        }
        sb.append("game language=").append(Minecraft.getMinecraft().gameSettings.language).append(", ");
        sb.append("locale=").append(Constants.getLocale());
        props.put("Environment", sb.toString());

        // Put all props in same format
        sb = new StringBuilder();
        for (Map.Entry<String, String> prop : props.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append(LogFormatter.LINEBREAK);
            }
            sb.append(prop.getKey()).append(": ").append(prop.getValue());
        }

        // Add Features
        sb.append(LogFormatter.LINEBREAK).append(FeatureManager.getPolicyDetails());

        // Add config files
        JourneymapClient jm = JourneymapClient.getInstance();
        List<? extends PropertiesBase> configs = Arrays.asList(
                JourneymapClient.getMiniMapProperties1(),
                JourneymapClient.getMiniMapProperties2(),
                JourneymapClient.getFullMapProperties(),
                JourneymapClient.getWaypointProperties(),
                JourneymapClient.getWebMapProperties(),
                JourneymapClient.getCoreProperties()
        );

        for (PropertiesBase config : configs)
        {
            // TODO: Only show non-default values?
            sb.append(LogFormatter.LINEBREAK).append(config);
        }

        return sb.toString();
    }

    /**
     * Return a handle to the log file used.
     *
     * @return
     */
    public static File getLogFile()
    {
        return new File(FileHandler.getJourneyMapDir(), LOG_FILE);
    }

    public static void logOnce(String text, Throwable throwable)
    {
        if (!singletonErrors.contains(text.hashCode()))
        {
            singletonErrors.add(text.hashCode());
            Journeymap.getLogger().error("{} (SUPPRESSED)", text);
            if (throwable != null)
            {
                Journeymap.getLogger().error(LogFormatter.toString(throwable));
            }
        }
        else
        {
            int count = singletonErrorsCounter.incrementAndGet();
            if (count > 1000)
            {
                singletonErrors.clear();
                singletonErrorsCounter.set(0);
            }
        }
    }

    public static class LogLevelStringProvider implements StringListProvider
    {
        @Override
        public List<String> getStrings()
        {
            Level[] levels = Level.values();
            String[] levelStrings = new String[levels.length];
            for (int i = 0; i < levels.length; i++)
            {
                levelStrings[i] = levels[i].toString();
            }
            return Arrays.asList(levelStrings);
        }

        @Override
        public String getDefaultString()
        {
            return Level.INFO.toString();
        }
    }
}
