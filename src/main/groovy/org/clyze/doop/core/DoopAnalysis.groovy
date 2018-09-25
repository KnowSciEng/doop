package org.clyze.doop.core

import groovy.transform.TypeChecked
import groovy.util.logging.Log4j
import heapdl.core.MemoryAnalyser
import org.apache.commons.io.FilenameUtils
import org.clyze.analysis.Analysis
import org.clyze.analysis.AnalysisOption
import org.clyze.doop.common.DoopErrorCodeException
import org.clyze.doop.common.FrontEnd
import org.clyze.doop.dex.DexInvoker
import org.clyze.doop.input.InputResolutionContext
import org.clyze.doop.python.PythonInvoker
import org.clyze.doop.wala.WalaInvoker
import org.clyze.utils.*
import org.codehaus.groovy.runtime.StackTraceUtils

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

import static org.apache.commons.io.FileUtils.*

/**
 * A DOOP analysis that holds all the relevant options (vars, paths, etc) and implements all the relevant steps.
 */
@Log4j
@TypeChecked
abstract class DoopAnalysis extends Analysis implements Runnable {

	/**
	 * The facts dir for the input facts
	 */
	protected File factsDir

	/**
	 * The underlying workspace
	 */
	protected File database

	/**
	 * The analysis input resolution mechanism
	 */
	InputResolutionContext ctx

	/**
	 * Used for invoking external commnands
	 */
	protected Executor executor

	/**
	 * Used for invoking the C preprocessor
	 */
	protected CPreprocessor cpp

	/**
	 * Total time for the soot invocation
	 */
	protected long factGenTime

	/**
	 * The suffix of information flow platforms.
	 */
	static final INFORMATION_FLOW_SUFFIX = "-sources-and-sinks"

	String getId() { options.USER_SUPPLIED_ID.value as String }

	String getName() { options.ANALYSIS.value.toString().replace(File.separator, "-") }

	File getOutDir() { options.OUT_DIR.value as File }

	File getCacheDir() { options.CACHE_DIR.value as File }

	List<File> getInputFiles() { options.INPUTS.value as List<File> }

	List<File> getLibraryFiles() { options.LIBRARIES.value as List<File> }

	/*
	 * Use a java-way to construct the instance (instead of using Groovy's automatically generated Map constructor)
	 * in order to ensure that internal state is initialized at one point and the init method is no longer required.
	 */

	protected DoopAnalysis(Map<String, AnalysisOption> options,
	                       InputResolutionContext ctx,
	                       Map<String, String> commandsEnvironment) {
		super(DoopAnalysisFamily.instance, options)
		this.ctx = ctx

		if (!options.X_START_AFTER_FACTS.value) {
			log.info "New $name analysis"
			log.info "Id       : $id"
			log.info "Inputs   : ${inputFiles.join(', ')}"
			log.info "Libraries: ${libraryFiles.join(', ')}"
		} else
			log.info "New $name analysis on user-provided facts at ${options.X_START_AFTER_FACTS.value} - id: $id"

		if (options.X_STOP_AT_FACTS.value)
			factsDir = new File(options.X_STOP_AT_FACTS.value.toString())
		else
			factsDir = new File(outDir, "facts")

		database = new File(outDir, "database")

		executor = new Executor(outDir, commandsEnvironment)
		cpp = new CPreprocessor(this, executor)

		new File(outDir, "meta").withWriter { it.write(this.toString()) }
	}

	String toString() {
		return [id: id, name: name, outDir: outDir, cacheDir: cacheDir, inputFiles: ctx.toString()]
				.collect { Map.Entry entry -> "${entry.key}=${entry.value}" }.join("\n") + "\n" +
				options.values().collect { AnalysisOption option -> option.toString() }.sort().join("\n") + "\n"
	}

	@Override
	abstract void run()

	/**
	 * Copies (or makes a symbolic link of) facts from an existing
	 * directory to the "facts" directory of an analysis. Used both
	 * when reading cached facts and when starting an analysis from
	 * existing facts.
	 *
	 * @param fromDir the existing directory containing the facts
	 */
	protected void linkOrCopyFacts(File fromDir) {
		if (options.X_SYMLINK_CACHED_FACTS.value) {
			try {
				Path fromDirPath = FileSystems.default.getPath(fromDir.canonicalPath)
				Files.createSymbolicLink(factsDir.toPath(), fromDirPath)
				return
			} catch (UnsupportedOperationException x) {
				System.err.println("Filesystem does not support symbolic links, copying directory instead...")
			}
		}

		factsDir.mkdirs()
		FileOps.copyDirContents(fromDir, factsDir)
	}

	protected void generateFacts() throws DoopErrorCodeException {
		deleteQuietly(factsDir)

		if (cacheDir.exists() && options.CACHE.value) {
			log.info "Using cached facts from $cacheDir"
			linkOrCopyFacts(cacheDir)
		} else if (cacheDir.exists() && options.X_START_AFTER_FACTS.value) {
			def importedFactsDir = options.X_START_AFTER_FACTS.value as String
			log.info "Using user-provided facts from ${importedFactsDir} in ${factsDir}"
			linkOrCopyFacts(new File(importedFactsDir))
		} else {
			factsDir.mkdirs()
			log.info "-- Fact Generation --"

			if (options.RUN_JPHANTOM.value) {
				runJPhantom()
			}

			def existingFactsDir = options.X_USE_EXISTING_FACTS.value as File
			if (existingFactsDir) {
				log.info "Expanding upon facts found in: $existingFactsDir.canonicalPath"
				linkOrCopyFacts(existingFactsDir)
			}

			Set<String> tmpDirs = [] as Set
			try {
				if (options.PYTHON.value) runPython(tmpDirs)
				else if (options.WALA_FACT_GEN.value) runFrontEnd(tmpDirs, FrontEnd.WALA)
				else if (options.DEX_FACT_GEN.value) runFrontEnd(tmpDirs, FrontEnd.DEX)
				else runFrontEnd(tmpDirs, FrontEnd.SOOT)
			} catch (all) {
				all = StackTraceUtils.deepSanitize all
				log.info all
				throw new DoopErrorCodeException(8)
			} finally {
				JHelper.cleanUp(tmpDirs)
			}

			if (options.X_UNIQUE_FACTS.value) {
				def timing = Helper.timing {
					factsDir.eachFileMatch(~/.*.facts/) { file ->
						def uniqueLines = file.readLines().toSet()
						def tmp = new File(factsDir, "${file.name}.tmp")
						tmp.withWriter { w -> uniqueLines.each { w.writeLine(it) } }
						tmp.renameTo(file)
					}
				}
				log.info "Time to make facts unique: $timing"
			}

			touch(new File(factsDir, "ApplicationClass.facts"))
			touch(new File(factsDir, "Properties.facts"))
			touch(new File(factsDir, "Dacapo.facts"))
			touch(new File(factsDir, "MainClass.facts"))

			if (options.DACAPO.value) {
				def benchmark = FilenameUtils.getBaseName(inputFiles[0].toString())
				def benchmarkCap = (benchmark as String).toLowerCase().capitalize()

				new File(factsDir, "Dacapo.facts").withWriter { w ->
					w << "dacapo.${benchmark}.${benchmarkCap}Harness" + "\t" + "<dacapo.parser.Config: void setClass(java.lang.String)>"
				}
			} else if (options.DACAPO_BACH.value) {
				def benchmark = FilenameUtils.getBaseName(inputFiles[0].toString())
				def benchmarkCap = (benchmark as String).toLowerCase().capitalize()

				new File(factsDir, "Dacapo.facts").withWriter { w ->
					w << "org.dacapo.harness.${benchmarkCap}" + "\t" + "<org.dacapo.parser.Config: void setClass(java.lang.String)>"
				}
			}

			if (options.TAMIFLEX.value) {
				File origTamFile = new File(options.TAMIFLEX.value.toString())

				new File(factsDir, "Tamiflex.facts").withWriter { w ->
					origTamFile.eachLine { line ->
						w << line
								.replaceFirst(/;[^;]*;$/, "")
								.replaceFirst(/;$/, ";0")
								.replaceFirst(/(^.*;.*)\.([^.]+;[0-9]+$)/) { full, first, second -> first + ";" + second + "\n" }
								.replaceAll(";", "\t").replaceFirst(/\./, "\t")
					}
				}
			}

			if (options.SCALER.value) {
				File origScalerFile = new File(options.SCALER.value.toString())
				File destScalerFile = new File(factsDir, "ScalerMethodContext.facts")
				Files.copy(origScalerFile.toPath(), destScalerFile.toPath())
			}

			if (options.ZIPPER.value) {
				File origZipperFile = new File(options.ZIPPER.value.toString())
				File destZipperFile = new File(factsDir, "ZipperPrecisionCriticalMethod.facts")
				Files.copy(origZipperFile.toPath(), destZipperFile.toPath())
			}

			if (!options.X_START_AFTER_FACTS.value) {
				if (options.HEAPDLS.value && !options.X_DRY_RUN.value) {
					runHeapDL(options.HEAPDLS.value.collect { File f -> f.canonicalPath })
				}

				log.info "Caching facts in $cacheDir"
				deleteQuietly(cacheDir)
				cacheDir.mkdirs()
				FileOps.copyDirContents(factsDir, cacheDir)
				new File(cacheDir, "meta").withWriter { BufferedWriter w -> w.write(cacheMeta()) }
			} else {
				log.warn "WARNING: Imported facts are not cached."
			}
			log.info "----"
		}

		if (options.MAIN_CLASS.value) {
			new File(factsDir, "MainClass.facts").withWriterAppend { w ->
				options.MAIN_CLASS.value.each { w.writeLine(it as String) }
			}
		}
	}

	private List<String> getInputArgsJars(Set<String> tmpDirs) {
		def inputArgs = inputFiles.collect() { File f -> ["-i", f.toString()] }.flatten() as Collection<String>
		return AARUtils.toJars(inputArgs as List<String>, false, tmpDirs)
	}

	private List<String> getDepsJars(Set<String> tmpDirs) {
		def deps = libraryFiles.collect { File f -> ["-ld", f.toString()] }.flatten() as Collection<String>
		return AARUtils.toJars(deps as List<String>, false, tmpDirs)
	}

	protected void runFrontEnd(Set<String> tmpDirs, FrontEnd frontEnd) {
		def platform = options.PLATFORM.value.toString().tokenize("_")[0]
		if (platform != "android" && platform != "java")
			throw new RuntimeException("Unsupported platform: ${platform}")

		def inputArgs = getInputArgsJars(tmpDirs)
		def deps = getDepsJars(tmpDirs)
		List<File> platforms = options.PLATFORMS.value as List<File>
		if (!platforms) {
			throw new RuntimeException("internal option '${options.PLATFORMS.name}' is empty")
		}

		Collection<String> params = ["--application-regex", options.APP_REGEX.value.toString()]

		if (options.FACT_GEN_CORES.value) {
			params += ["--fact-gen-cores", options.FACT_GEN_CORES.value.toString()]
		}

		if (options.X_FACTS_SUBSET.value) {
			params += ["--facts-subset", options.X_FACTS_SUBSET.value.toString()]
		}

		if (options.INFORMATION_FLOW_EXTRA_CONTROLS.value) {
			params += ["--extra-sensitive-controls", options.INFORMATION_FLOW_EXTRA_CONTROLS.value.toString()]
		}

		if (options.SEED.value) {
			params += ["--seed", options.SEED.value.toString()]
		}

		if (options.SPECIAL_CONTEXT_SENSITIVITY_METHODS.value) {
			params += ["--special-cs-methods", options.SPECIAL_CONTEXT_SENSITIVITY_METHODS.value.toString()]
		}

		if (options.X_DRY_RUN.value) {
			params += ["--noFacts"]
		}

		if (options.X_R_OUT_DIR.value) {
			params += ["--R-out-dir", options.X_R_OUT_DIR.value.toString()]
		}

		params.addAll(["-d", factsDir.toString()] + inputArgs)
		deps.addAll(platforms.collect { lib -> ["-l", lib.toString()] }.flatten() as Collection<String>)

		if (frontEnd == FrontEnd.SOOT) {
			runSoot(platform, deps, platforms, params)
		} else if (frontEnd == FrontEnd.WALA) {
			runWala(platform, deps, platforms, params)
		} else if (frontEnd == FrontEnd.DEX) {
			if (options.X_STOP_AT_FACTS.value) {
				runDexFactGen(platform, deps, platforms, params, tmpDirs)
			} else {
				System.err.println("Option --${options.DEX_FACT_GEN.name} only works with --${options.X_STOP_AT_FACTS.name}")
				throw new DoopErrorCodeException(15)
			}
		} else {
			println("Unknown front-end: " + frontEnd)
		}
	}

	protected void runSoot(String platform, Collection<String> deps, List<File> platforms, Collection<String> params) {
		params += [ "--full" ] + deps

		if (platform == "android") {
			// This uses all platformLibs.
			// params = ["--android-jars"] + platformLibs.collect({ f -> f.getAbsolutePath() })
			// This uses just platformLibs[0], assumed to be android.jar.
			params.addAll(["--android-jars", platforms.first().absolutePath])
		}

		if (options.SSA.value) {
			params += ["--ssa"]
		}

		if (!options.RUN_JPHANTOM.value) {
			params += ["--allow-phantom"]
		}

		if (options.DONT_REPORT_PHANTOMS.value) {
			params += ["--dont-report-phantoms"]
		}

		if (options.RUN_FLOWDROID.value) {
			params += ["--run-flowdroid"]
		}

		if (options.X_IGNORE_WRONG_STATICNESS.value) {
			params += ["--ignoreWrongStaticness"]
		}

		if (options.GENERATE_JIMPLE.value) {
			params += ["--generate-jimple"]
		}

		log.debug "Params of soot: ${params.join(' ')}"

		factGenTime = Helper.timing {
			//We invoke soot reflectively using a separate class-loader to be able
			//to support multiple soot invocations in the same JVM @ server-side.
			//TODO: Investigate whether this approach may lead to memory leaks,
			//not only for soot but for all other Java-based tools, like jphantom.
			//In such a case, we should invoke all Java-based tools using a
			//separate process.
			ClassLoader loader = sootClassLoader()
			def success = Helper.execJava(loader, "org.clyze.doop.soot.Main", params.toArray(new String[params.size()]))
			if (!success)
				throw new RuntimeException("Soot fact generation error")
		}
		log.info "Soot fact generation time: ${factGenTime}"
	}

	protected void runWala(String platform, Collection<String> deps, List<File> platforms, Collection<String> params) {
		if (platform == "android") {
			// This uses all platformLibs.
			// params = ["--full"] + depArgs + ["--android-jars"] + platformLibs.collect({ f -> f.getAbsolutePath() })
			// This uses just platformLibs[0], assumed to be android.jar.
			params.addAll(["--android-jars", platforms.first().absolutePath])
		} else if (platform != "java") {
			throw new RuntimeException("Unsupported platform: ${platform}")
		}

		params = params + deps

		log.debug "Params of wala: ${params.join(' ')}"

		try {
			factGenTime = Helper.timing {
				//We invoke soot reflectively using a separate class-loader to be able
				//to support multiple soot invocations in the same JVM @ server-side.
				//TODO: Investigate whether this approach may lead to memory leaks,
				//not only for soot but for all other Java-based tools, like jphantom.
				//In such a case, we should invoke all Java-based tools using a
				//separate process.
				WalaInvoker wala = new WalaInvoker()
				wala.main(params.toArray(new String[params.size()]))
			}
		} catch(walaError){
			walaError.printStackTrace()
			throw new RuntimeException("Wala fact generation Error: $walaError", walaError)
		}
		log.info "Wala fact generation time: ${factGenTime}"
	}

	protected void runDexFactGen(String platform, Collection<String> deps, List<File> platforms, Collection<String> params, Set<String> tmpDirs) {

		// params += [ "--print-phantoms" ]

		if (options.APK_DECOMPRESS_DIR.value) {
			params += ["--apk-decompress-dir", options.APK_DECOMPRESS_DIR.value.toString()]
		}

		log.debug "Params of dex front-end: ${params.join(' ')}"

		try {
			DexInvoker.main(params.toArray(new String[params.size()]))
		} catch (Exception ex) {
			ex.printStackTrace()
		}
	}

	protected void runPython(Set<String> tmpDirs) {
		Collection<String> params = []
		Collection<String> depArgs = []
		def inputArgs = getInputArgsJars(tmpDirs)
		def deps = getDepsJars(tmpDirs)

		def platform = options.PLATFORM.value.toString().tokenize("_")[0]
		assert platform == "python"

		if (options.FACT_GEN_CORES.value) {
			params += ["--fact-gen-cores", options.FACT_GEN_CORES.value.toString()]
		}
		if (options.GENERATE_JIMPLE.value) {
			params += ["--generate-ir"]
		}
		if (options.SINGLE_FILE_ANALYSIS.value) {
			params += ["--single-file-analysis"]
		}
		//depArgs = (platformLibs.collect{ lib -> ["-l", lib.toString()] }.flatten() as Collection<String>) + deps
		params = params + inputArgs + depArgs + ["-d", factsDir.toString()]

		log.debug "Params of wala: ${params.join(' ')}"

		try {
			factGenTime = Helper.timing {
				PythonInvoker wala = new PythonInvoker()
				wala.main(params.toArray(new String[params.size()]))
			}
		} catch(walaError){
			walaError.printStackTrace()
			throw new RuntimeException("Wala fact generation Error: $walaError", walaError)
		}
		log.info "Wala fact generation time: ${factGenTime}"
	}

	protected void runJPhantom() {
		log.info "-- Running jphantom to generate complement jar --"

		String jar = inputFiles[0].toString()
		String jarName = FilenameUtils.getBaseName(jar)
		String jarExt = FilenameUtils.getExtension(jar)
		String newJar = "${jarName}-complemented.${jarExt}"
		String[] params = [jar, "-o", "${outDir}/$newJar", "-d", "${outDir}/phantoms", "-v", "0"]
		log.debug "Params of jphantom: ${params.join(' ')}"

		//we invoke the main method reflectively to avoid adding jphantom as a compile-time dependency
		ClassLoader loader = phantomClassLoader()
		Helper.execJava(loader, "org.clyze.jphantom.Driver", params)

		//set the jar of the analysis to the complemented one
		inputFiles[0] = FileOps.findFileOrThrow("$outDir/$newJar", "jphantom invocation failed")
	}

	protected String cacheMeta() {
		Collection<String> inputJars = inputFiles.collect {
			File file -> file.toString()
		}
		Collection<String> cacheOptions = options.values().findAll {
			it.forCacheID
		}.collect {
			AnalysisOption option -> option.toString()
		}.sort()
		return (inputJars + cacheOptions).join("\n")
	}

	/**
	 * Creates a new class loader for running jphantom
	 */
	protected ClassLoader phantomClassLoader() { copyOfCurrentClasspath() }

	/**
	 * Creates a new class loader for running soot
	 */
	protected ClassLoader sootClassLoader() { copyOfCurrentClasspath() }

	protected ClassLoader copyOfCurrentClasspath() {
		URLClassLoader loader = this.class.classLoader as URLClassLoader
		URL[] classpath = loader.URLs
		return new URLClassLoader(classpath, null as ClassLoader)
	}

	protected void runHeapDL(List<String> filenames) {
		try {
			MemoryAnalyser memoryAnalyser = new MemoryAnalyser(filenames, options.HEAPDL_NOSTRINGS.value ? false : true)
			int n = memoryAnalyser.getAndOutputFactsToDB(factsDir, "2ObjH")
			log.info("Generated " + n + " addditional facts from memory dump")
		} catch (Exception e) {
			e.printStackTrace()
		}
	}

	protected final void handleImportDynamicFacts() {
		if (options.IMPORT_DYNAMIC_FACTS.value) {
			File f = new File(options.IMPORT_DYNAMIC_FACTS.value.toString())
			if (f.exists()) {
				throw new RuntimeException("Facts file ${f.canonicalPath} already exists, cannot overwrite it with imported file of same name.")
			} else {
				copyFileToDirectory(f, factsDir)
			}
		}
	}
}
