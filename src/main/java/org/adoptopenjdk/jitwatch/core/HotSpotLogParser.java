/*
 * Copyright (c) 2013, 2014 Chris Newland.
 * Licensed under https://github.com/AdoptOpenJDK/jitwatch/blob/master/LICENSE-BSD
 * Instructions: https://github.com/AdoptOpenJDK/jitwatch/wiki
 */
package org.adoptopenjdk.jitwatch.core;

import org.adoptopenjdk.jitwatch.model.*;
import org.adoptopenjdk.jitwatch.util.ClassUtil;
import org.adoptopenjdk.jitwatch.util.ParseUtil;
import org.adoptopenjdk.jitwatch.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.*;

public class HotSpotLogParser implements ILogParser, IMemberFinder
{
	private static final Logger logger = LoggerFactory.getLogger(HotSpotLogParser.class);

	private JITDataModel model;

	private String vmCommand = null;

	private boolean isTweakVMLog = false;

	private boolean reading = false;

	boolean hasTraceClassLoad = false;
	
	private boolean hasParseError = false;
	private String errorDialogTitle;
	private String errorDialogBody;

	private IMetaMember currentMember = null;

	private IJITListener logListener = null;
	private ILogParseErrorListener errorListener = null;

	private boolean inHeader = false;

	private long currentLineNumber;

	private JITWatchConfig config = new JITWatchConfig();

	private TagProcessor tagProcessor;

	private AssemblyProcessor asmProcessor;

	private SplitLog splitLog = new SplitLog();

	private ParsedClasspath parsedClasspath = new ParsedClasspath();

	public HotSpotLogParser(IJITListener logListener)
	{
		model = new JITDataModel();

		this.logListener = logListener;
	}

	public void setConfig(JITWatchConfig config)
	{
		this.config = config;
	}

	@Override
	public JITWatchConfig getConfig()
	{
		return config;
	}

	@Override
	public SplitLog getSplitLog()
	{
		return splitLog;
	}

	private void configureDisposableClassLoader()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("configureDisposableClassLoader()");
		}

		List<String> configuredClassLocations = config.getClassLocations();
		List<String> parsedClassLocations = parsedClasspath.getClassLocations();

		int configuredClasspathCount = configuredClassLocations.size();
		int parsedClasspathCount = parsedClassLocations.size();

		List<URL> classpathURLList = new ArrayList<URL>(configuredClasspathCount + parsedClasspathCount);

		for (String filename : configuredClassLocations)
		{
			URI uri = new File(filename).toURI();

			logListener.handleLogEntry("Adding configured classpath: " + uri.toString());

			try
			{
				classpathURLList.add(uri.toURL());
			}
			catch (MalformedURLException e)
			{
				logger.error("Could not create URL: {} ", uri, e);
			}
		}

		for (String filename : parsedClasspath.getClassLocations())
		{
			if (!configuredClassLocations.contains(filename))
			{
				URI uri = new File(filename).toURI();

				logListener.handleLogEntry("Adding parsed classpath: " + uri.toString());

				try
				{
					classpathURLList.add(uri.toURL());
				}
				catch (MalformedURLException e)
				{
					logger.error("Could not create URL: {} ", uri, e);
				}
			}
		}

		ClassUtil.initialise(classpathURLList);
	}

	private void logEvent(JITEvent event)
	{
		if (logListener != null)
		{
			logListener.handleJITEvent(event);
		}
	}

	private void logError(String entry)
	{
		if (logListener != null)
		{
			logListener.handleErrorEntry(entry);
		}
	}

	@Override
	public JITDataModel getModel()
	{
		return model;
	}

	@Override
	public void reset()
	{
		getModel().reset();

		splitLog.clear();

		hasTraceClassLoad = false;

		isTweakVMLog = false;

		hasParseError = false;
		errorDialogTitle = null;
		errorDialogBody = null;

		reading = false;

		inHeader = false;

		currentMember = null;

		// tell listener to reset any data
		logListener.handleReadStart();
		
		vmCommand = null;

		currentLineNumber = 0;

		tagProcessor = new TagProcessor();

		asmProcessor = new AssemblyProcessor(this);
	}

	@Override
	public void processLogFile(File hotspotLog, ILogParseErrorListener errorListener)
	{
		reset();

		this.errorListener = errorListener;

		splitLogFile(hotspotLog);

		logSplitStats();

		parseLogFile();
	}

	private void parseLogFile()
	{
		parseHeaderLines();

		buildParsedClasspath();

		configureDisposableClassLoader();

		buildClassModel();

		parseLogCompilationLines();

		parseAssemblyLines();

		checkIfErrorDialogNeeded();

		logListener.handleReadComplete();
	}

	private void checkIfErrorDialogNeeded()
	{
		if (!hasParseError)
		{
			if (!hasTraceClassLoad)
			{
				hasParseError = true;

				errorDialogTitle = "Missing VM Switch -XX:+TraceClassLoading";
				errorDialogBody = "JITWatch requires the -XX:+TraceClassLoading VM switch to be used.\nPlease recreate your log file with this switch enabled.";
			}
		}

		if (hasParseError)
		{
			errorListener.handleError(errorDialogTitle, errorDialogBody);
		}
	}

	private void parseHeaderLines()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("parseHeaderLines()");
		}

		for (String line : splitLog.getHeaderLines())
		{
			if (!skipLine(line, SKIP_HEADER_TAGS))
			{
				Tag tag = tagProcessor.processLine(line);

				if (tag != null)
				{
					handleTag(tag);
				}
			}
		}
	}

	private void parseLogCompilationLines()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("parseLogCompilationLines()");
		}

		for (String line : splitLog.getLogCompilationLines())
		{			
			if (!skipLine(line, SKIP_BODY_TAGS))
			{
				Tag tag = tagProcessor.processLine(line);

				if (tag != null)
				{
					handleTag(tag);
				}
			}
		}
	}

	private void parseAssemblyLines()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("parseAssemblyLines()");
		}

		for (String line : splitLog.getAssemblyLines())
		{
			asmProcessor.handleLine(line);
		}

		asmProcessor.complete();
	}

	private void splitLogFile(File hotspotLog)
	{
		reading = true;

		BufferedReader reader = null;

		try
		{
			reader = new BufferedReader(new FileReader(hotspotLog), 65536);

			String currentLine = reader.readLine();

			while (reading && currentLine != null)
			{
				try
				{
					String trimmedLine = currentLine.trim();

					if (trimmedLine.length() > 0)
					{
						char firstChar = trimmedLine.charAt(0);

						if (firstChar == C_OPEN_ANGLE || firstChar == C_OPEN_SQUARE_BRACKET || firstChar == C_AT)
						{
							currentLine = trimmedLine;
						}

						handleLogLine(currentLine);
					}
				}
				catch (Exception ex)
				{
					logger.error("Exception handling: '{}'", currentLine, ex);
				}

				currentLine = reader.readLine();
			}
		}
		catch (IOException ioe)
		{
			logger.error("Exception while splitting log file", ioe);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (Exception e)
				{
					logger.error("Could not close reader");
				}
			}
		}
	}

	private void logSplitStats()
	{
		logger.info("Header lines        : {}", splitLog.getHeaderLines().size());
		logger.info("ClassLoader lines   : {}", splitLog.getClassLoaderLines().size());
		logger.info("LogCompilation lines: {}", splitLog.getLogCompilationLines().size());
		logger.info("Assembly lines      : {}", splitLog.getAssemblyLines().size());
	}

	@Override
	public void stopParsing()
	{
		reading = false;
	}

	private boolean skipLine(final String line, final Set<String> skipSet)
	{
		boolean isSkip = false;

		for (String skip : skipSet)
		{
			if (line.startsWith(skip))
			{
				isSkip = true;
				break;
			}
		}

		return isSkip;
	}

	private void handleLogLine(final String inCurrentLine)
	{
		String currentLine = inCurrentLine;

		currentLine = currentLine.replace(S_ENTITY_LT, S_OPEN_ANGLE);
		currentLine = currentLine.replace(S_ENTITY_GT, S_CLOSE_ANGLE);

		if (TAG_TTY.equals(currentLine))
		{
			inHeader = false;
			return;
		}
		else if (currentLine.startsWith(TAG_XML))
		{
			inHeader = true;
		}

		if (inHeader)
		{
			// HotSpot log header XML can have text nodes so consume all lines
			splitLog.addHeaderLine(currentLine);
		}
		else
		{
			if (currentLine.startsWith(S_OPEN_ANGLE))
			{
				// After the header, XML nodes do not have text nodes
				splitLog.addLogCompilationLine(currentLine);
			}
			else if (currentLine.startsWith(LOADED))
			{
				splitLog.addClassLoaderLine(currentLine);
			}
			else if (currentLine.startsWith(S_AT))
			{
				// possible PrintCompilation was enabled as well as
				// LogCompilation?
				// jmh does this with perf annotations
				// Ignore this line
			}
			else
			{
				// need to cope with nmethod appearing on same line as last hlt
				// 0x0000 hlt <nmethod compile_id= ....

				int indexNMethod = currentLine.indexOf(S_OPEN_ANGLE + TAG_NMETHOD);

				if (indexNMethod != -1)
				{
					if (DEBUG_LOGGING)
					{
						logger.debug("detected nmethod tag mangled with assembly");
					}

					String assembly = currentLine.substring(0, indexNMethod);

					String remainder = currentLine.substring(indexNMethod);

					splitLog.addAssemblyLine(assembly);

					handleLogLine(remainder);
				}
				else
				{
					splitLog.addAssemblyLine(currentLine);
				}
			}
		}

		currentLineNumber++;
	}

	private void handleTag(Tag tag)
	{
		String tagName = tag.getName();

		switch (tagName)
		{
		case TAG_VM_VERSION:
			handleVmVersion(tag);
			break;

		case TAG_TASK_QUEUED:
			handleTagQueued(tag);
			break;

		case TAG_NMETHOD:
			handleTagNMethod(tag);
			break;

		case TAG_TASK:
			handleTagTask(tag);
			break;

		case TAG_START_COMPILE_THREAD:
			handleStartCompileThread(tag);
			break;

		case TAG_VM_ARGUMENTS:
			handleTagVmArguments(tag);
			break;

		default:
			break;
		}
	}

	private void handleVmVersion(Tag tag)
	{
		String release = tag.getNamedChildren(TAG_RELEASE).get(0).getTextContent();

		model.setVmVersionRelease(release);

		List<Tag> tweakVMTags = tag.getNamedChildren(TAG_TWEAK_VM);

		if (tweakVMTags.size() == 1)
		{
			isTweakVMLog = true;
			logger.info("TweakVM detected!");
		}
	}

	private void handleTagVmArguments(Tag tag)
	{
		vmCommand = tag.getNamedChildren(TAG_COMMAND).get(0).getTextContent();
		logger.info("VM Command: {}", vmCommand);
	}

	private void handleStartCompileThread(Tag tag)
	{
		model.getJITStats().incCompilerThreads();
		String threadName = tag.getAttribute(ATTR_NAME);

		if (theThreadIsNotFound(threadName))
		{
			logger.error("Thread name not found (attribute '{}' missing in tag).\n", ATTR_NAME);
			return;
		}

		if (threadName.startsWith(C1))
		{
			tagProcessor.setCompiler(CompilerName.C1);
		}
		else if (threadName.startsWith(C2))
		{
			tagProcessor.setCompiler(CompilerName.C2);
		}
		else
		{
			logger.error("Unexpected compiler name: {}", threadName);
		}
	}

	private boolean theThreadIsNotFound(String threadName)
	{
		return threadName == null;
	}

	public IMetaMember findMemberWithSignature(String logSignature)
	{
		IMetaMember result = null;

		try
		{
			result = ParseUtil.findMemberWithSignature(model, logSignature);
		}
		catch (Exception ex)
		{
			logger.warn("Exception parsing signature: {}", logSignature, ex);
		}

		if (result == null)
		{
			logError("Could not parse line " + currentLineNumber + " : " + logSignature);
		}

		return result;
	}

	private void handleTagQueued(Tag tag)
	{
		handleMethodLine(tag, EventType.QUEUE);
	}

	private void handleTagNMethod(Tag tag)
	{
		String attrCompiler = tag.getAttribute(ATTR_COMPILER);

		if (attrCompiler != null)
		{
			if (C1.equals(attrCompiler))
			{
				handleMethodLine(tag, EventType.NMETHOD_C1);
			}
			else if (C2.equals(attrCompiler))
			{
				handleMethodLine(tag, EventType.NMETHOD_C2);
			}
			else
			{
				logError("Unexpected Compiler attribute: " + attrCompiler);
			}
		}
		else
		{
			String attrCompileKind = tag.getAttribute(ATTR_COMPILE_KIND);

			if (attrCompileKind != null && C2N.equals(attrCompileKind))
			{
				handleMethodLine(tag, EventType.NMETHOD_C2N);
			}
			else
			{
				logError("Missing Compiler attribute " + tag);
			}
		}
	}

	private void handleTagTask(Tag tag)
	{
		handleMethodLine(tag, EventType.TASK);

		Tag tagCodeCache = tag.getFirstNamedChild(TAG_CODE_CACHE);

		if (tagCodeCache != null)
		{
			// copy timestamp from parent <task> tag used for graphing code
			// cache
			String stamp = tag.getAttribute(ATTR_STAMP);
			tagCodeCache.getAttrs().put(ATTR_STAMP, stamp);

			model.addCodeCacheTag(tagCodeCache);
		}

		Tag tagTaskDone = tag.getFirstNamedChild(TAG_TASK_DONE);

		if (tagTaskDone != null)
		{
			handleTaskDone(tagTaskDone);
		}
	}

	private void handleMethodLine(Tag tag, EventType eventType)
	{
		Map<String, String> attrs = tag.getAttrs();

		String attrMethod = attrs.get(ATTR_METHOD);

		if (attrMethod != null)
		{
			attrMethod = attrMethod.replace(S_SLASH, S_DOT);

			IMetaMember member = handleMember(attrMethod, attrs, eventType);

			if (member != null)
			{
				member.addJournalEntry(tag);
			}
		}
	}

	private IMetaMember handleMember(String signature, Map<String, String> attrs, EventType type)
	{
		IMetaMember metaMember = findMemberWithSignature(signature);

		String stampAttr = attrs.get(ATTR_STAMP);
		long stampTime = ParseUtil.parseStamp(stampAttr);

		if (metaMember != null)
		{
			switch (type)
			{
			case QUEUE:
			{
				metaMember.setQueuedAttributes(attrs);
				JITEvent queuedEvent = new JITEvent(stampTime, type, metaMember.toString());
				model.addEvent(queuedEvent);
				logEvent(queuedEvent);
			}
				break;
			case NMETHOD_C1:
			case NMETHOD_C2:
			case NMETHOD_C2N:
			{
				metaMember.setCompiledAttributes(attrs);
				metaMember.getMetaClass().incCompiledMethodCount();
				model.updateStats(metaMember);

				JITEvent compiledEvent = new JITEvent(stampTime, type, metaMember.toString());
				model.addEvent(compiledEvent);
				logEvent(compiledEvent);
			}
				break;
			case TASK:
			{
				metaMember.addCompiledAttributes(attrs);
				currentMember = metaMember;
			}
				break;
			}
		}

		return metaMember;
	}

	private void handleTaskDone(Tag tag)
	{
		Map<String, String> attrs = tag.getAttrs();

		if (attrs.containsKey("nmsize"))
		{
			long nmsize = Long.parseLong(attrs.get("nmsize"));
			model.addNativeBytes(nmsize);
		}

		if (currentMember != null)
		{
			currentMember.addCompiledAttributes(attrs);

			// prevents attr overwrite by next task_done if next member not
			// found due to classpath issues
			currentMember = null;
		}
	}

	private void buildParsedClasspath()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("buildParsedClasspath()");
		}

		for (String line : splitLog.getClassLoaderLines())
		{
			buildParsedClasspath(line);
		}
	}

	private void buildClassModel()
	{
		if (DEBUG_LOGGING)
		{
			logger.debug("buildClassModel()");
		}

		for (String line : splitLog.getClassLoaderLines())
		{
			buildClassModel(line);
		}
	}

	private void buildParsedClasspath(String inCurrentLine)
	{
		if (!hasTraceClassLoad)
		{
			hasTraceClassLoad = true;
		}

		final String FROM_SPACE = "from ";

		String originalLocation = null;

		int fromSpacePos = inCurrentLine.indexOf(FROM_SPACE);

		if (fromSpacePos != -1)
		{
			originalLocation = inCurrentLine.substring(fromSpacePos + FROM_SPACE.length(), inCurrentLine.length() - 1);
		}

		if (originalLocation != null && originalLocation.startsWith(S_FILE_COLON))
		{
			originalLocation = originalLocation.substring(S_FILE_COLON.length());

			parsedClasspath.addClassLocation(originalLocation);
		}
	}

	private void buildClassModel(String inCurrentLine)
	{
		String fqClassName = StringUtil.getSubstringBetween(inCurrentLine, LOADED, S_SPACE);

		if (fqClassName != null)
		{
			addToClassModel(fqClassName);
		}
	}

	private void addToClassModel(String fqClassName)
	{
		Class<?> clazz = null;

		try
		{
			clazz = ClassUtil.loadClassWithoutInitialising(fqClassName);

			if (clazz != null)
			{
				model.buildMetaClass(fqClassName, clazz);
			}
		}
		catch (ClassNotFoundException cnf)
		{
			logError("ClassNotFoundException: '" + fqClassName + C_QUOTE);
		}
		catch (NoClassDefFoundError ncdf)
		{
			logError("NoClassDefFoundError: '" + fqClassName + C_SPACE + "requires " + ncdf.getMessage() + C_QUOTE);
		}
		catch (UnsupportedClassVersionError ucve)
		{
			hasParseError = true;
			errorDialogTitle = "UnsupportedClassVersionError for class " + fqClassName;
			errorDialogBody = "Could not load " + fqClassName + " as the class file version is too recent for this JVM.";

			logError("UnsupportedClassVersionError! Tried to load a class file with an unsupported format (later version than this JVM)");
			logger.error("Class file for {} created in a later JVM version", fqClassName, ucve);
		}
		catch (Throwable t)
		{
			// Possibly a VerifyError
			logger.error("Could not addClassToModel {}", fqClassName, t);
			logError("Exception: '" + fqClassName + C_QUOTE);
		}
	}

	@Override
	public boolean hasParseError()
	{
		return hasParseError;
	}

	@Override
	public boolean isTweakVMLog()
	{
		return isTweakVMLog;
	}

	@Override
	public String getVMCommand()
	{
		return vmCommand;
	}

}