package org.clyze.doop.core

import groovy.util.logging.Log4j
import org.clyze.analysis.AnalysisOption
import org.clyze.analysis.BooleanAnalysisOption
import org.clyze.analysis.IntegerAnalysisOption
import org.clyze.utils.FileOps

/**
 * Doop initialization and supported options.
 */
@Log4j
class Doop {

	static final List<String> OPTIONS_EXCLUDED_FROM_ID_GENERATION = [
			"LOGICBLOX_HOME",
			"LD_LIBRARY_PATH",
			"BLOXBATCH",
			"BLOX_OPTS",
			"CACHE",
			"PLATFORMS_LIB"
	]

	// Not the best pattern, but limits the source code size :)
	static String doopHome
	static String doopOut
	static String doopCache
	static String doopLog
	static String souffleAnalysesCache
	static String logicPath
	static String souffleLogicPath
	static String factsPath
	static String souffleFactsPath
	static String addonsPath
	static String souffleAddonsPath
	static String analysesPath
	static String souffleAnalysesPath
	static String soufflePythonPath
	static String soufflePythonAnalysesPath

	/**
	 * Initializes Doop.
	 * @param homePath The doop home directory (sets the doopHome variable, required).
	 * @param outPath The doop out directory (sets the doopOut variable, optional, defaults to 'out' under doopHome).
	 * @param cachePath The doop cache directory (sets the doopCache variable, optional, defaults to 'cache' under doopHome).
	 * @return The doop home directory.
	 */
	static void initDoop(String homePath, String outPath, String cachePath, String logPath) {
		doopHome = homePath
		if (!doopHome) throw new RuntimeException("DOOP_HOME environment variable is not set")
		FileOps.findDirOrThrow(doopHome, "DOOP_HOME environment variable is invalid: $doopHome")

		doopOut = outPath ?: "$doopHome/out"
		doopCache = cachePath ?: "$doopHome/cache"
		doopLog = logPath ?: "$doopHome/build/logs"
		souffleAnalysesCache = "$doopCache/souffle-analyses"
		logicPath = "$doopHome/logic"
		souffleLogicPath = "$doopHome/souffle-logic"
		factsPath = "$logicPath/facts"
		souffleFactsPath = "$souffleLogicPath/facts"
		addonsPath = "$logicPath/addons"
		souffleAddonsPath = "$souffleLogicPath/addons"
		analysesPath = "$logicPath/analyses"
		souffleAnalysesPath = "$souffleLogicPath/analyses"
		soufflePythonPath = "$souffleLogicPath/python"
		soufflePythonAnalysesPath = "$soufflePythonPath/analyses"

		[doopOut, doopCache, doopLog, souffleAnalysesCache].each {
			def f = new File(it)
			f.mkdirs()
			FileOps.findDirOrThrow(f, "Could not create directory: $it")
		}
	}

	/**
	 * Initializes Doop using the default environment variables.
	 */
	static void initDoopFromEnv() {
		Doop.initDoop(System.getenv("DOOP_HOME"), System.getenv("DOOP_OUT"), System.getenv("DOOP_CACHE"), System.getenv("DOOP_LOG"))
	}

	/**
	 * Creates the default analysis options.
	 * @return Map < String , AnalysisOptions > .
	 */
	static Map<String, AnalysisOption> createDefaultAnalysisOptions() {
		DoopAnalysisFamily.instance.supportedOptions().collectEntries { [(it.id): it.clone()] }
	}

	/**
	 * Creates the analysis options by overriding the default options with the
	 * ones contained in the given CLI options. An option is set only if
	 * filtered (the supplied filter returns true for the option).
	 * @param cli - the CLI option accessor.
	 * @param filter - optional filter to apply before setting the option.
	 * @return the default analysis options overridden by the values contained in the CLI option accessor.
	 */
	static Map<String, AnalysisOption> overrideDefaultOptionsWithCLI(OptionAccessor cli, Closure<Boolean> filter) {
		def options = createDefaultAnalysisOptions()
		overrideOptionsWithCLI(options, cli, filter)
		return options
	}

	/**
	 * Creates the analysis options by overriding the default options with the
	 * ones contained in the given properties and CLI options. A CLI option
	 * superseeds a property one. An option is set only if filtered (the
	 * supplied filter returns true for the option).
	 * @param props - the properties.
	 * @param cli - the CLI option accessor.
	 * @param filter - optional filter to apply before setting the option.
	 * @return the default analysis options overridden by the values contained in the properties.
	 */
	static Map<String, AnalysisOption> overrideDefaultOptionsWithPropertiesAndCLI(Properties properties,
	                                                                              OptionAccessor cli,
	                                                                              Closure<Boolean> filter) {
		Map<String, AnalysisOption> options = createDefaultAnalysisOptions()
		overrideOptionsWithProperties(options, properties, filter)
		overrideOptionsWithCLI(options, cli, filter)
		return options
	}

	/**
	 * Overrides the values of the map (the options values) with the values
	 * contained in the properties. An option is set only if filtered (the
	 * supplied filter returns true for the option).
	 * @param options - the options to override.
	 * @param properties - the properties to use.
	 * @param filter - the filter to apply.
	 * @return the original map of options with its values overridden by the ones contained in the properties.
	 */
	static void overrideOptionsWithProperties(Map<String, AnalysisOption> options,
	                                          Properties properties,
	                                          Closure<Boolean> filter) {
		if (properties && properties.size() > 0) {
			properties.each { key, value ->
				AnalysisOption option = options.get(key.toUpperCase())
				if (option && value && value.trim().length() > 0) {
					boolean filtered = filter ? filter.call(option) : true
					if (filtered) {
						if (option.argName) {
							option.value = value
						} else {
							// value.toBoolean will fail on null
							// value as Boolean will return null, on null
							// value as boolean will return false, on null
							option.value = value as boolean
						}
					}
				}
			}
		}
	}

	/**
	 * Overrides the values of the map (the options values) with the values
	 * contained in the CLI options. An option is set only if filtered (the
	 * supplied filter returns true for the option).
	 * @param options - the options to override.
	 * @param properties - the properties to use.
	 * @param filter - the filter to apply.
	 * @return the original map of options with its values overridden by the ones contained in the CLI options.
	 */
	static void overrideOptionsWithCLI(Map<String, AnalysisOption> options, OptionAccessor cli, Closure<Boolean> filter) {
		options.values().each { AnalysisOption option ->
			def name = option.name
			if (name) {
				log.debug "Processing $name"
				// NOTE: Obscure cli builder feature: to get the value of a cl option
				// as a List, you need to append an s to its short name
				def optionValue = option.multipleValues ? cli[("${name}s")] : cli[(name)]
				if (optionValue) { //Only true-ish values are of interest (false or null values are ignored)
					if (filter ? filter.call(option) : true) {
						// If the cl option has an argument, its value defines the value of the
						// respective analysis option
						if (option.argName) {
							if (option instanceof BooleanAnalysisOption)
								option.value = optionValue.toBoolean()
							else if (option instanceof IntegerAnalysisOption)
								option.value = optionValue.toInteger()
							else
								option.value = optionValue
						}
						// If the cl option has no argument and it's a boolean flag which
						// is now set to true (all boolean options are false by default)
						else {
							option.value = true
						}
					}
				}
			}
		}
	}

}
