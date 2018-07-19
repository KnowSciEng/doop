package org.clyze.doop.wala;

import org.clyze.doop.util.filter.ClassFilter;

import java.util.ArrayList;
import java.util.List;

public class WalaParameters {
    List<String> _inputs = new ArrayList<>();
    List<String> _appLibraries = new ArrayList<>();
    List<String> _platformLibraries = new ArrayList<>();
    String _javaPath = null;
    String _outputDir = null;
    String _extraSensitiveControls = "";
    ClassFilter applicationClassFilter;
    String appRegex = "**";
    Integer _cores = null;
    boolean _android = false;
    boolean _generateIR = false;
    String _androidJars = null;

    public void setInputs(List<String> inputs) {
        this._inputs = inputs;
    }

    public List<String> getInputs() {
        return this._inputs;
    }

    public void setAppLibraries(List<String> libraries) {
        this._appLibraries = libraries;
    }

    public List<String> getAppLibraries() {
        return this._appLibraries;
    }

    public void setPlatformLibraries(List<String> libraries) {
        this._platformLibraries = libraries;
    }

    public List<String> getPlatformLibraries() {
        return this._platformLibraries;
    }

    public List<String> getInputsAndLibraries() {
        List<String> ret = new ArrayList<>();
        ret.addAll(this._inputs);
        ret.addAll(this._appLibraries);
        ret.addAll(this._platformLibraries);
        return ret;
    }

}
