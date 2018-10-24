package org.clyze.doop.soot;

import heros.solver.CountingThreadPoolExecutor;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.clyze.doop.common.DoopErrorCodeException;
import soot.PackManager;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.options.Options;

/**
 * This class gathers modified functionality that Doop needs from Soot.
 */
class DoopAddons {

    public static void retrieveAllSceneClassesBodies(Integer _cores) {
        // The old coffi front-end is not thread-safe
        boolean runSeq = (_cores == null) || Options.v().coffi();
        int threadNum = runSeq ? 1 : _cores;
        CountingThreadPoolExecutor executor =  new CountingThreadPoolExecutor(threadNum,
                                                                              threadNum, 30, TimeUnit.SECONDS,
                                                                              new LinkedBlockingQueue<>());
        Iterator<SootClass> clIt = Scene.v().getClasses().snapshotIterator();
        while( clIt.hasNext() ) {
            SootClass cl = clIt.next();
            //note: the following is a snapshot iterator;
            //this is necessary because it can happen that phantom methods
            //are added during resolution
            for (SootMethod m : cl.getMethods())
                if (m.isConcrete())
                    executor.execute(m::retrieveActiveBody);
        }
        // Wait till all method bodies have been loaded
        try {
            executor.awaitCompletion();
            executor.shutdown();
        } catch (InterruptedException e) {
            // Something went horribly wrong
            throw new RuntimeException("Could not wait for loader threads to "
                                       + "finish: " + e.getMessage(), e);
        }
        // If something went wrong, we tell the world
        if (executor.getException() != null)
            throw (RuntimeException) executor.getException();
    }

    // Call non-public method: PackManager.v().retrieveAllBodies()
    public static void retrieveAllBodies() throws DoopErrorCodeException {
        PackManager pm = PackManager.v();
        try {
            Method rAB = pm.getClass().getDeclaredMethod("retrieveAllBodies");
            rAB.setAccessible(true);
            rAB.invoke(pm);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            System.err.println("Could not call Soot method retrieveAllBodies():");
            ex.printStackTrace();
            throw new DoopErrorCodeException(11, ex);
        }
    }

    // Call non-public method: PackManager.v().writeClass(sootClass)
    public static void writeClass(SootClass sootClass) throws DoopErrorCodeException {
        PackManager pm = PackManager.v();
        try {
            Method wC = pm.getClass().getDeclaredMethod("writeClass", SootClass.class);
            wC.setAccessible(true);
            wC.invoke(pm, sootClass);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            System.err.println("Could not call Soot method writeClass(): ");
            ex.printStackTrace();
            throw new DoopErrorCodeException(12, ex);
        }
    }

    /**
     * Upstream Soot does not structure generated Jimple by package, which is
     * expected by the server.
     */
    public static void structureJimpleFiles(String outDir) {
        boolean movedMsg = false;
        String jimpleDirPath = outDir + File.separatorChar + "jimple";
        File[] outDirFiles = new File(outDir).listFiles();
        if (outDirFiles == null)
            return;

        final String JIMPLE_EXT = ".shimple";

        int dirsCreated = 0;
        for (File f : outDirFiles) {
            String fName = f.getName();
            if (fName.endsWith(JIMPLE_EXT)) {
                if (!movedMsg) {
                    System.out.println("Moving " + JIMPLE_EXT + " files to structure under " + jimpleDirPath);
                    movedMsg = true;
                }
                String base = fName.substring(0, fName.length() - JIMPLE_EXT.length()).replace('.', File.separatorChar);
                fName = jimpleDirPath + File.separatorChar + base + JIMPLE_EXT;
                File newFile = new File(fName);
                if (newFile.getParentFile().mkdirs())
                    dirsCreated++;
                try {
                    Files.move(f.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    System.err.println("Error moving " + f);
                    ex.printStackTrace();
                }
            }
        }
        if (dirsCreated > 0)
            System.out.println("Jimple output restructured, created " + dirsCreated + " directories.");
    }

    /**
     * Returns true if Doop uses the upstream version of Soot, false if it uses the fork.
     */
    public static boolean usingUpstream() {
        try {
            Objects.requireNonNull(Class.forName("soot.jimple.toolkits.scalar.DoopRenamer"));
            return false;
        } catch (ClassNotFoundException ex) {
            return true;
        }
    }
}
