package org.clyze.doop.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import static org.clyze.doop.common.PredicateFile.*;

/**
 * Common functionality that a fact writer for Java bytecode can reuse.
 */
public class JavaFactWriter {

    protected static final String L_OP = "1";
    protected static final String R_OP = "2";
    protected final Database _db;

    protected JavaFactWriter(Database db) {
        this._db = db;
    }

    public static String str(int i) {
        return String.valueOf(i);
    }

    protected String writeStringConstant(String constant) {
        String raw = FactEncoders.encodeStringConstant(constant);

        String result;
        if(raw.length() <= 256)
            result = raw;
        else
            result = "<<HASH:" + raw.hashCode() + ">>";

        _db.add(STRING_RAW, result, raw);
        _db.add(STRING_CONST, result);

        return result;
    }

    protected String hashMethodNameIfLong(String methodRaw) {
        if (methodRaw.length() <= 1024)
            return methodRaw;
        else
            return "<<METHOD HASH:" + methodRaw.hashCode() + ">>";
    }

    private void writeClassArtifact(String artifact, String className, String subArtifact) {
        _db.add(CLASS_ARTIFACT, artifact, className, subArtifact);
    }

    private void writeAndroidKeepMethod(String methodSig) {
        _db.add(ANDROID_KEEP_METHOD, "<" + methodSig + ">");
    }

    private void writeAndroidKeepClass(String className) {
        _db.add(ANDROID_KEEP_CLASS, className);
    }

    private void writeProperty(String path, String key, String value) {
        String pathId = writeStringConstant(path);
        String keyId = writeStringConstant(key);
        String valueId = writeStringConstant(value);
        _db.add(PROPERTIES, pathId, keyId, valueId);
    }

    protected void writeMethodHandleConstant(String heap, String handleName) {
        _db.add(METHOD_HANDLE_CONSTANT, heap, handleName);
    }

    protected void writeFormalParam(String methodId, String var, String type, int i) {
        _db.add(FORMAL_PARAM, str(i), methodId, var);
        _db.add(VAR_TYPE, var, type);
        _db.add(VAR_DECLARING_METHOD, var, methodId);
    }

    protected void writeThisVar(String methodId, String thisVar, String type) {
        _db.add(THIS_VAR, methodId, thisVar);
        _db.add(VAR_TYPE, thisVar, type);
        _db.add(VAR_DECLARING_METHOD, thisVar, methodId);
    }

    public void writeApplication(String applicationName) {
	_db.add(ANDROID_APPLICATION, applicationName);
    }

    public void writeActivity(String activity) {
        _db.add(ACTIVITY, activity);
    }

    public void writeService(String service) {
        _db.add(SERVICE, service);
    }

    public void writeContentProvider(String contentProvider) {
        _db.add(CONTENT_PROVIDER, contentProvider);
    }

    public void writeBroadcastReceiver(String broadcastReceiver) {
        _db.add(BROADCAST_RECEIVER, broadcastReceiver);
    }

    public void writeCallbackMethod(String callbackMethod) {
        _db.add(CALLBACK_METHOD, callbackMethod);
    }

    public void writeLayoutControl(Integer id, String viewClassName, Integer parentID, String appRId, String androidRId) {
        _db.add(LAYOUT_CONTROL, id.toString(), viewClassName, parentID.toString());
    }

    public void writeSensitiveLayoutControl(Integer id, String viewClassName, Integer parentID) {
        _db.add(SENSITIVE_LAYOUT_CONTROL, id.toString(), viewClassName, parentID.toString());
    }

    public void writePreliminaryFacts(BasicJavaSupport java, Parameters params) {
        PropertyProvider propertyProvider = java.getPropertyProvider();

        // Read all stored properties files
        for (Map.Entry<String, Properties> entry : propertyProvider.getProperties().entrySet()) {
            String path = entry.getKey();
            Properties properties = entry.getValue();

            for (String propertyName : properties.stringPropertyNames()) {
                String propertyValue = properties.getProperty(propertyName);
                writeProperty(path, propertyName, propertyValue);
            }
        }

        try {
            processSeeds(params._seed);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Last step of writing facts, after all classes have been processed.
     *
     * @param java  the object supporting basic Java functionality
     */
    public void writeLastFacts(BasicJavaSupport java) {
        Map<String, Set<ArtifactEntry>> artifactToClassMap = java.getArtifactToClassMap();

        System.out.println("Generated artifact-to-class map for " + artifactToClassMap.size() + " artifacts.");
        for (String artifact : artifactToClassMap.keySet())
            for (ArtifactEntry ae : artifactToClassMap.get(artifact))
                writeClassArtifact(artifact, ae.className, ae.subArtifact);
    }

    // The extra sensitive controls are given as a String
    // "id1,type1,parentId1,id2,type2,parentId2,...".
    public void writeExtraSensitiveControls(Parameters parameters) {
        if (parameters.getExtraSensitiveControls().equals("")) {
            return;
        }
        String[] parts = parameters.getExtraSensitiveControls().split(",");
        int partsLen = parts.length;
        if (partsLen % 3 != 0) {
            System.err.println("List size (" + partsLen + ") not a multiple of 3: \"" + parameters.getExtraSensitiveControls() + "\"");
            return;
        }
        for (int i = 0; i < partsLen; i += 3) {
            String control = parts[i] + "," + parts[i+1] + "," + parts[i+2];
            try {
                int controlId = Integer.parseInt(parts[i]);
                String typeId = parts[i+1].trim();
                int parentId  = Integer.parseInt(parts[i+2]);
                System.out.println("Adding sensitive layout control: " + control);
                writeSensitiveLayoutControl(controlId, typeId, parentId);
            } catch (Exception ex) {
                System.err.println("Ignoring control: " + control);
            }
        }
    }

    private void processSeeds(String seed) throws IOException {
        if (seed != null) {
            System.out.println("Reading seeds from: " + seed);
            try (Stream<String> stream = Files.lines(Paths.get(seed))) {
                stream.forEach(this::processSeedFileLine);
            }
        }
    }

    private void processSeedFileLine(String line) {
        if (line.contains("("))
            writeAndroidKeepMethod(line);
        else if (!line.contains(":"))
            writeAndroidKeepClass(line);
    }

    protected void writeMethodDeclaresException(String methodId, String exceptionType) {
        _db.add(METHOD_DECL_EXCEPTION, exceptionType, methodId);
    }

    protected void writePhantomType(String t) {
        _db.add(PHANTOM_TYPE, t);
    }

    protected void writePhantomMethod(String sig) {
        _db.add(PHANTOM_METHOD, sig);
    }

    protected void writeLocal(String local, String type, String method) {
        _db.add(VAR_TYPE, local, type);
        _db.add(VAR_DECLARING_METHOD, local, method);
    }

    protected void writeArrayTypes(String arrayType, String componentType) {
        _db.add(ARRAY_TYPE, arrayType);
        _db.add(COMPONENT_TYPE, arrayType, componentType);
    }

    protected void writeAssignUnop(String insn, int index, String local, String methId) {
        _db.add(ASSIGN_UNOP, insn, str(index), local, methId);
    }

    protected void writeClassModifier(String c, String modifier) {
        _db.add(CLASS_MODIFIER, modifier, c);
    }

    protected void writeOperatorAt(String insn, String op) {
        _db.add(OPERATOR_AT, insn, op);
    }

    protected void writeIfVar(String insn, String branch, String local) {
        _db.add(IF_VAR, insn, branch, local);
    }

    protected void writeDummyIfVar(String insn, String local) {
        _db.add(DUMMY_IF_VAR, insn, local);
    }

    protected void writeAssignBinop(String insn, int index, String local, String methodId) {
        _db.add(ASSIGN_BINOP, insn, str(index), Representation.local(m, left), methodId);
    }
}
