package org.clyze.doop.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import org.objectweb.asm.ClassReader;

/**
 * This class gathers Java-specific code (such as JAR handling).
 */
public class BasicJavaSupport {

    protected final Set<String> classesInApplicationJars;
    protected final Set<String> classesInLibraryJars;
    protected final Set<String> classesInDependencyJars;
    private final Map<String, Set<ArtifactEntry>> artifactToClassMap;
    private final PropertyProvider propertyProvider;

    public BasicJavaSupport() {
        this.classesInApplicationJars = new HashSet<>();
        this.classesInLibraryJars = new HashSet<>();
        this.classesInDependencyJars = new HashSet<>();
        this.propertyProvider = new PropertyProvider();
        this.artifactToClassMap = new HashMap<>();
    }

    /**
     * Helper method to read classes and property files from input archives.
     *
     * @param parameters the list of all the given parameters
     *
     */
    public void preprocessInputs(Parameters parameters) throws IOException {
        for (String filename : parameters.getInputs()) {
            System.out.println("Preprocessing application: " + filename);
            preprocessInput(classesInApplicationJars, filename);
        }
        for (String filename : parameters.getPlatformLibs()) {
            System.out.println("Preprocessing platform library: " + filename);
            preprocessInput(classesInLibraryJars, filename);
        }
        for (String filename : parameters.getDependencies()) {
            System.out.println("Preprocessing dependency: " + filename);
            preprocessInput(classesInDependencyJars, filename);
        }
    }

    /**
     * Preprocess an input archive.
     *
     * @param classSet   appropriate set to add class names
     * @param filename   the input filename
     */
    private void preprocessInput(Set<String> classSet, String filename) throws IOException {
        JarEntry entry;
        try (JarInputStream jin = new JarInputStream(new FileInputStream(filename));
             JarFile jarFile = new JarFile(filename)) {
            /* List all JAR entries */
            while ((entry = jin.getNextJarEntry()) != null) {
                /* Skip directories */
                if (entry.isDirectory())
                    continue;

                String entryName = entry.getName();
                if (entryName.endsWith(".class")) {
                    try {
                        ClassReader reader = new ClassReader(jarFile.getInputStream(entry));
                        String className = reader.getClassName().replace("/", ".");
                        classSet.add(className);
                        String artifact = (new File(jarFile.getName())).getName();
                        registerArtifactClass(artifact, className, "-");
                    } catch (IllegalArgumentException e) {
                        System.err.println("-- Problematic .class file \"" + entryName + "\"");
                    }
                } else if (entryName.endsWith(".properties")) {
                    propertyProvider.addProperties(jarFile.getInputStream(entry), filename);
                } /* Skip non-class files and non-property files */
            }
        }
    }

    public PropertyProvider getPropertyProvider() {
        return propertyProvider;
    }

    public Map<String, Set<ArtifactEntry>> getArtifactToClassMap() {
        return artifactToClassMap;
    }

    /**
     * Registers a class with its container artifact.
     * @param artifact     the file name of the artifact containing the class
     * @param className    the name of the class
     * @param subArtifact  the sub-artifact (such as "classes.dex" for APKs)
     */
    public void registerArtifactClass(String artifact, String className, String subArtifact) {
        ArtifactEntry ae = new ArtifactEntry(className, subArtifact);
        if (!artifactToClassMap.containsKey(artifact)) {
            Set<ArtifactEntry> artifactClasses = new HashSet<>();
            artifactClasses.add(ae);
            artifactToClassMap.put(artifact, artifactClasses);
        } else
            artifactToClassMap.get(artifact).add(ae);
    }

    public Set<String> getClassesInApplicationJars() {
        return classesInApplicationJars;
    }

    public Set<String> getClassesInLibraryJars() {
        return classesInLibraryJars;
    }

    public Set<String> getClassesInDependencyJars() {
        return classesInDependencyJars;
    }
}
