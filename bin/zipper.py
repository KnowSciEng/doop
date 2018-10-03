#!/usr/bin/env python
import os
import shutil
import sys

# This script should be executed from the root directory of Doop.

# ----------------- configuration -------------------------
DOOP = './doop'  # './doopOffline'
PRE_ANALYSIS = 'context-insensitive'
APP = 'temp'
SEP = '\\t'

ZIPPER_CACHE = 'zipper/cache'
ZIPPER_OUT = 'zipper/out'
THREAD = 16  # use multithreading to accelerate Zipper
ONLY_LIB = True
DOOP_OUT = 'out'
# ---------------------------------------------------------
RESET = '\033[0m'
YELLOW = '\033[33m'
BOLD = '\033[1m'

def run_pre_analysis(initArgs):
    args = list(initArgs)
    for opt in ['-a', '--analysis']:
        if opt in args:
            i = args.index(opt)
            del args[i]  # delete '-a' or '--analysis'
            del args[i]  # delete the analysis argument
    args = [DOOP] + args
    args = args + ['-a', PRE_ANALYSIS]
    args = args + ['--zipper-pre']
    args = args + ['--cache']
    args = args + ['--id', APP + "-zipper-ci"]
    cmd = ' '.join(args)
    print YELLOW + BOLD + 'Running pre-analysis ...' + RESET
    # print cmd
    os.system(cmd)

    ci_analysis_facts = os.path.join(DOOP_OUT, 'context-insensitive', APP + '-zipper-ci', 'facts')

    cache_facts_dir = os.path.join(ZIPPER_CACHE, APP, 'facts')
    if not os.path.exists(cache_facts_dir):
        shutil.copytree(ci_analysis_facts, cache_facts_dir)
    else:
        shutil.rmtree(cache_facts_dir)
        shutil.copytree(ci_analysis_facts, cache_facts_dir)

def dump_required_doop_results(app, db_dir, dump_dir):
    INPUT = {
        'APP_METHODS': 'Stats_Simple_Application_ReachableMethod',
        'VPT': 'Stats_Simple_InsensVarPointsTo'
    }

    REQUIRED_INPUT = [
        'APP_METHODS', 'ARRAY_LOAD', 'ARRAY_STORE',
        'CALL_EDGE', 'CALL_RETURN_TO', 'CALLSITEIN', 'DIRECT_SUPER_TYPE',
        'INST_CALL_RECV', 'INST_METHODS', 'INSTANCE_LOAD', 'INSTANCE_STORE',
        'INTERPROCEDURAL_ASSIGN', 'LOCAL_ASSIGN', 'METHOD_MODIFIER',
        'OBJ_TYPE', 'OBJECT_ASSIGN', 'OBJECT_IN',
        'PARAMS', 'RET_VARS', 'SPECIAL_OBJECTS',
        'THIS_VAR', 'VAR_IN', 'VPT'
    ]

    def dump_doop_results(db_dir, dump_dir, app, query):
        file_name = INPUT.get(query, query) + '.csv'
        from_path = os.path.join(db_dir, file_name)
        dump_path = os.path.join(dump_dir, '%s.%s' % (app, query))
        if not os.path.exists(dump_dir):
            os.mkdir(dump_dir)
        shutil.copyfile(from_path, dump_path)

    print 'Dumping doop analysis results %s ...' % app
    for query in REQUIRED_INPUT:
        dump_doop_results(db_dir, dump_dir, app, query)


def run_zipper(app, cache_dir, out_dir):
    cmd = './gradlew zipper -Pargs=\''
    cmd += ' -sep %s ' % SEP
    cmd += ' -app %s ' % app
    cmd += ' -cache %s ' % cache_dir
    cmd += ' -out %s ' % out_dir
    cmd += ' -thread %d ' % THREAD
    if ONLY_LIB:
        cmd += ' -only-lib '
    cmd += '\''
    print cmd
    os.system(cmd)

    zipper_file = os.path.join(out_dir, '%s-ZipperPrecisionCriticalMethod.facts' % app)
    from_path = os.path.join(ZIPPER_OUT, app, '%s-ZipperPrecisionCriticalMethod.facts' % app)
    dump_path = os.path.join(os.path.join(ZIPPER_CACHE, app, 'facts', '%s-ZipperPrecisionCriticalMethod.facts' % app))
    shutil.copyfile(from_path, dump_path)

    return zipper_file


def run_main_analysis(args, zipper_file):
    args = [DOOP] + args
    args = args + ['--zipper', zipper_file]
    args = args + ['--Xstart-after-facts', os.path.join(ZIPPER_CACHE, APP, 'facts')]
    cmd = ' '.join(args)
    print YELLOW + BOLD + 'Running main (Zipper-guided) analysis ...' + RESET
    # print cmd
    os.system(cmd)


def run_cached(args):
    zipper_file = run_zipper(APP, os.path.join(ZIPPER_CACHE, APP), os.path.join(ZIPPER_OUT, APP))
    run_main_analysis(args, zipper_file)


def run(args):
    if not os.path.exists("zipper"):
        os.mkdir("zipper")
    run_pre_analysis(args)
    ci_analysis_database = os.path.join(DOOP_OUT, 'context-insensitive', APP + '-zipper-ci', 'database')
    dump_required_doop_results(APP, ci_analysis_database, os.path.join(ZIPPER_CACHE, APP))
    zipper_file = run_zipper(APP, os.path.join(ZIPPER_CACHE, APP), os.path.join(ZIPPER_OUT, APP))
    run_main_analysis(args, zipper_file)


if __name__ == '__main__':
    if sys.argv[-1] == 'cache':
        APP = sys.argv[-2]
        run(sys.argv[1:-2])
    else:
        APP = sys.argv[-1]
        run(sys.argv[1:-1])
