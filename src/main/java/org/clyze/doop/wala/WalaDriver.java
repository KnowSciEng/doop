package org.clyze.doop.wala;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.*;

class WalaDriver {

    private final WalaThreadFactory _factory;
    private final boolean _android;

    private final ExecutorService _executor;
    private int _classCounter;
    private Set<IClass> _tmpClassGroup;
    private final int _totalClasses;
    private final IAnalysisCacheView _cache;

    WalaDriver(WalaThreadFactory factory, int totalClasses,
           Integer cores, boolean android, IAnalysisCacheView cache) {
        _factory = factory;
        _classCounter = 0;
        _tmpClassGroup = new HashSet<>();
        _totalClasses = totalClasses;
        _android = android;
        _cache = cache;
        int _cores = cores == null? Runtime.getRuntime().availableProcessors() : cores;

        System.out.println("Fact generation cores: " + _cores);

        if (_cores > 2) {
            _executor = new ThreadPoolExecutor(_cores /2, _cores, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        } else {
            // No scheduling happens in the case of one core/thread. ("Tasks are
            // guaranteed to execute sequentially, and no more than one task will
            // be active at any given time.")
            _executor = Executors.newSingleThreadExecutor();
        }
    }

    void doSequentially(Iterator<IClass> iClasses, WalaFactWriter writer, String outDir) {
        while (iClasses.hasNext()) {
            _tmpClassGroup.add(iClasses.next());
        }

        WalaFactGenerator factGenerator = new WalaFactGenerator(writer, _tmpClassGroup, outDir, _android, _cache);
        //factGenerator.generate(dummyMain, new Session());
        //writer.writeAndroidEntryPoint(dummyMain);
        factGenerator.run();
    }

    void doInParallel(Set<IClass> classesToProcess) {
        classesToProcess.forEach(this::generate);

    }

    private void generate(IClass curClass) {
        _classCounter++;
        _tmpClassGroup.add(curClass);

        int _classSplit = 80;
        if ((_classCounter % _classSplit == 0) || (_classCounter == _totalClasses)) {
            Runnable runnable = _factory.newFactGenRunnable(_tmpClassGroup, _cache);
            _executor.execute(runnable);
            _tmpClassGroup = new HashSet<>();
        }
    }

    void shutdown() {
        _executor.shutdown();
        try {
            _executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
        }
    }
}
