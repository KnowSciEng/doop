package org.clyze.doop.soot;

import org.clyze.doop.common.BasicJavaSupport;
import org.clyze.doop.common.Database;
import org.clyze.doop.common.JavaFactWriter;
import org.clyze.doop.common.PredicateFile;
import org.clyze.doop.common.SessionCounter;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.toolkits.typing.fast.BottomType;
import soot.tagkit.*;
import soot.util.backend.ASMBackendUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.clyze.doop.common.JavaRepresentation.*;
import static org.clyze.doop.common.PredicateFile.*;

/**
 * FactWriter determines the format of a fact and adds it to a
 * database.
 */
class FactWriter extends JavaFactWriter {
    private final Representation _rep;
    private final Map<String, Type> _varTypeMap = new ConcurrentHashMap<>();
    private final boolean _reportPhantoms;
    private final Set<Object> seenPhantoms = new HashSet<>();

    FactWriter(Database db, boolean moreStrings,
               Representation rep, boolean reportPhantoms) {
        super(db, moreStrings);
        _rep = rep;
        _reportPhantoms = reportPhantoms;
    }

    String writeMethod(SootMethod m) {
        String methodRaw = _rep.signature(m);
        String methodId = hashMethodNameIfLong(methodRaw);
        String arity = Integer.toString(m.getParameterCount());

        _db.add(STRING_RAW, methodId, methodRaw);
        _db.add(METHOD, methodId, _rep.simpleName(m), Representation.params(m), writeType(m.getDeclaringClass()), writeType(m.getReturnType()), ASMBackendUtils.toTypeDesc(m.makeRef()), arity);
        if (m.getTag("VisibilityAnnotationTag") != null) {
            VisibilityAnnotationTag vTag = (VisibilityAnnotationTag) m.getTag("VisibilityAnnotationTag");
            for (AnnotationTag aTag : vTag.getAnnotations()) {
                writeMethodAnnotation(methodId, soot.coffi.Util.v().jimpleTypeOfFieldDescriptor(aTag.getType()).toQuotedString());
            }
        }
        if (m.getTag("VisibilityParameterAnnotationTag") != null) {
            VisibilityParameterAnnotationTag vTag = (VisibilityParameterAnnotationTag) m.getTag("VisibilityParameterAnnotationTag");

            ArrayList<VisibilityAnnotationTag> annList = vTag.getVisibilityAnnotations();
            for (int i = 0; i < annList.size(); i++) {
                if (annList.get(i) != null) {
                    for (AnnotationTag aTag : annList.get(i).getAnnotations()) {
                        _db.add(PARAM_ANNOTATION, methodId, str(i), soot.coffi.Util.v().jimpleTypeOfFieldDescriptor(aTag.getType()).toQuotedString());
                    }
                }
            }
        }
        return methodId;
    }

    void writeAndroidEntryPoint(SootMethod m) {
        _db.add(ANDROID_ENTRY_POINT, _rep.signature(m));
    }

    void writeClassOrInterfaceType(SootClass c) {
        String classStr = c.getName();
        boolean isInterface = c.isInterface();
        if (isInterface && c.isPhantom()) {
            if (_reportPhantoms)
                System.out.println("Interface " + classStr + " is phantom.");
            writePhantomType(c);
        }
        _db.add(isInterface ? INTERFACE_TYPE : CLASS_TYPE, classStr);
        writeClassHeap(Representation.classConstant(c), classStr);
        if (c.getTag("VisibilityAnnotationTag") != null) {
            VisibilityAnnotationTag vTag = (VisibilityAnnotationTag) c.getTag("VisibilityAnnotationTag");
            for (AnnotationTag aTag : vTag.getAnnotations()) {
                _db.add(CLASS_ANNOTATION, classStr, soot.coffi.Util.v().jimpleTypeOfFieldDescriptor(aTag.getType()).toQuotedString());
            }
        }
    }

    void writeDirectSuperclass(SootClass sub, SootClass sup) {
        _db.add(DIRECT_SUPER_CLASS, writeType(sub), writeType(sup));
    }

    void writeDirectSuperinterface(SootClass clazz, SootClass iface) {
        _db.add(DIRECT_SUPER_IFACE, writeType(clazz), writeType(iface));
    }

    private String writeType(SootClass c) {
        // The type itself is already taken care of by writing the
        // SootClass declaration, so we don't actually write the type
        // here, and just return the string.
        return c.getName();
    }

    private String writeType(Type t) {
        String result = t.toString();

        if (t instanceof ArrayType) {
            Type componentType = ((ArrayType) t).getElementType();
            writeArrayTypes(result, writeType(componentType));
        }
        else if (t instanceof PrimType || t instanceof NullType ||
                t instanceof RefType || t instanceof VoidType || t instanceof BottomType) {
            // taken care of by the standard facts
        }
        else
            throw new RuntimeException("Don't know what to do with type " + t);

        return result;
    }

    void writePhantomType(Type t) {
        if (_reportPhantoms)
            System.out.println("Type " + t + " is phantom.");
        writePhantomType(writeType(t));
    }

    private void writePhantomType(SootClass c) {
        writePhantomType(writeType(c));
    }

    void writePhantomMethod(SootMethod m) {
        String sig = writeMethod(m);
        if (_reportPhantoms)
            System.out.println("Method " + sig + " is phantom.");
        writePhantomMethod(sig);
    }

    void writePhantomBasedMethod(SootMethod m) {
        String sig = writeMethod(m);
        if (_reportPhantoms)
            System.out.println("Method signature " + sig + " contains phantom types.");
        _db.add(PHANTOM_BASED_METHOD, sig);
    }

    void writeEnterMonitor(SootMethod m, EnterMonitorStmt stmt, Local var, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ENTER_MONITOR, insn, str(index), Representation.local(m, var), methodId);
    }

    void writeExitMonitor(SootMethod m, ExitMonitorStmt stmt, Local var, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(EXIT_MONITOR, insn, str(index), Representation.local(m, var), methodId);
    }

    void writeAssignLocal(SootMethod m, Stmt stmt, Local to, Local from, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);
        writeAssignLocal(insn, index, Representation.local(m, from), Representation.local(m, to), methodId);
    }

    void writeAssignThisToLocal(SootMethod m, Stmt stmt, Local to, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);
        writeAssignLocal(insn, index, Representation.thisVar(m), Representation.local(m, to), methodId);
    }

    void writeAssignLocal(SootMethod m, Stmt stmt, Local to, ParameterRef ref, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);
        writeAssignLocal(insn, index, Representation.param(m, ref.getIndex()), Representation.local(m, to), methodId);
    }

    void writeAssignInvoke(SootMethod inMethod, Stmt stmt, Local to, InvokeExpr expr, Session session) {
        String insn = writeInvokeHelper(inMethod, stmt, expr, session);

        _db.add(ASSIGN_RETURN_VALUE, insn, Representation.local(inMethod, to));
    }

    void writeAssignHeapAllocation(SootMethod m, Stmt stmt, Local l, AnyNewExpr expr, Session session) {
        String heap = Representation.heapAlloc(m, expr, session);


        _db.add(NORMAL_HEAP, heap, writeType(expr.getType()));

        if (expr instanceof NewArrayExpr) {
            NewArrayExpr newArray = (NewArrayExpr) expr;
            Value sizeVal = newArray.getSize();

            if (sizeVal instanceof IntConstant) {
                IntConstant size = (IntConstant) sizeVal;

                if(size.value == 0)
                    _db.add(EMPTY_ARRAY, heap);
            }
        }

        // statement
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);
        _db.add(ASSIGN_HEAP_ALLOC, insn, str(index), heap, Representation.local(m, l), methodId, ""+getLineNumberFromStmt(stmt));
    }

    private static int getLineNumberFromStmt(Stmt stmt) {
        LineNumberTag tag = (LineNumberTag) stmt.getTag("LineNumberTag");
        return tag == null ? 0 : tag.getLineNumber();
    }

    private Type getComponentType(ArrayType type) {
        // Soot calls the component type of an array type the "element
        // type", which is rather confusing, since in an array type
        // A[][][], the JVM Spec defines A to be the element type, and
        // A[][] is the component type.
        return type.getElementType();
    }

    /**
     * NewMultiArray is slightly complicated because an array needs to
     * be allocated separately for every dimension of the array.
     */
    void writeAssignNewMultiArrayExpr(SootMethod m, Stmt stmt, Local l, NewMultiArrayExpr expr, Session session) {
        writeAssignNewMultiArrayExprHelper(m, stmt, l, Representation.local(m,l), expr, (ArrayType) expr.getType(), session);
    }

    private void writeAssignNewMultiArrayExprHelper(SootMethod m, Stmt stmt, Local l, String assignTo, NewMultiArrayExpr expr, ArrayType arrayType, Session session) {
        String heap = Representation.heapMultiArrayAlloc(m, /* expr, */ arrayType, session);
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);


        String methodId = writeMethod(m);

        _db.add(NORMAL_HEAP, heap, writeType(arrayType));
        _db.add(ASSIGN_HEAP_ALLOC, insn, str(index), heap, assignTo, methodId, ""+getLineNumberFromStmt(stmt));

        Type componentType = getComponentType(arrayType);
        if (componentType instanceof ArrayType) {
            String childAssignTo = Representation.newLocalIntermediate(m, l, session);
            writeAssignNewMultiArrayExprHelper(m, stmt, l, childAssignTo, expr, (ArrayType) componentType, session);
            int storeInsnIndex = session.calcUnitNumber(stmt);
            String storeInsn = Representation.instruction(m, stmt, storeInsnIndex);

            _db.add(STORE_ARRAY_INDEX, storeInsn, str(storeInsnIndex), childAssignTo, assignTo, methodId);
            writeLocal(childAssignTo, writeType(componentType), methodId);
        }
    }

    // The commented-out code below is what used to be in Doop2. It is not
    // equivalent to code in old Doop. I (YS) tried to have a more compatible
    // approach for comparison purposes.
    /*
    public void writeAssignNewMultiArrayExpr(SootMethod m, Stmt stmt, Local l, NewMultiArrayExpr expr, Session session) {
        // what is a normal object?
        String heap = _rep.heapAlloc(m, expr, session);

        _db.addInput("NormalObject",
                _db.asEntity(heap),
                writeType(expr.getType()));

        // local variable to assign the current array allocation to.
        String assignTo = _rep.local(m, l);

        Type type = (ArrayType) expr.getType();
        int dimensions = 0;
        while(type instanceof ArrayType)
            {
                ArrayType arrayType = (ArrayType) type;

                // make sure we store the type
                writeType(type);

                type = getComponentType(arrayType);
                dimensions++;
            }

        Type elementType = type;

        int index = session.calcInstructionNumber(stmt);
        String rep = _rep.instruction(m, stmt, index);

        _db.addInput("AssignMultiArrayAllocation",
                _db.asEntity(rep),
                _db.asIntColumn(str(index)),
                _db.asEntity(heap),
                _db.asIntColumn(str(dimensions)),
                _db.asEntity(assignTo),
                _db.asEntity("Method", _rep.method(m)));

    // idea: do generate the heap allocations, but not the assignments
    // (to array indices). Do store the type of those heap allocations
    }
    */

    void writeAssignStringConstant(SootMethod m, Stmt stmt, Local l, StringConstant s, Session session) {
        String constant = s.toString();
        String content = constant.substring(1, constant.length() - 1);
        String heapId = writeStringConstant(content);

        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_HEAP_ALLOC, insn, str(index), heapId, Representation.local(m, l), methodId, ""+getLineNumberFromStmt(stmt));
    }

    void writeAssignNull(SootMethod m, Stmt stmt, Local l, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_NULL, insn, str(index), Representation.local(m, l), methodId);
    }

    void writeAssignNumConstant(SootMethod m, Stmt stmt, Local l, NumericConstant constant, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_NUM_CONST, insn, str(index), constant.toString(), Representation.local(m, l), methodId);
    }

    private void writeAssignMethodHandleConstant(SootMethod m, Stmt stmt, Local l, MethodHandle constant, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String handleMethod = constant.getMethodRef().toString();
        String heap = methodHandleConstant(handleMethod);
        String methodId = writeMethod(m);

        SigInfo si = new SigInfo(constant.getMethodRef());
        writeMethodHandleConstant(heap, handleMethod, si.retType, si.paramTypes, si.arity);
        _db.add(ASSIGN_HEAP_ALLOC, insn, str(index), heap, Representation.local(m, l), methodId, "0");
    }

    void writeAssignClassConstant(SootMethod m, Stmt stmt, Local l, ClassConstant constant, Session session) {
        writeAssignClassConstant(m, stmt, l, new ClassConstantInfo(constant), session);
    }

    void writeAssignClassConstant(SootMethod m, Stmt stmt, Local l, ClassConstantInfo info, Session session) {
        if (info.isMethodType)
            writeMethodTypeConstant(info.heap);
        else
            writeClassHeap(info.heap, info.actualType);

        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        // REVIEW: the class object is not explicitly written. Is this always ok?
        _db.add(ASSIGN_HEAP_ALLOC, insn, str(index), info.heap, Representation.local(m, l), methodId, "0");
    }

    void writeAssignCast(SootMethod m, Stmt stmt, Local to, Local from, Type t, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_CAST, insn, str(index), Representation.local(m, from), Representation.local(m, to), writeType(t), methodId);
    }

    void writeAssignCastNumericConstant(SootMethod m, Stmt stmt, Local to, NumericConstant constant, Type t, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_CAST_NUM_CONST, insn, str(index), constant.toString(), Representation.local(m, to), writeType(t), methodId);
    }

    void writeAssignCastNull(SootMethod m, Stmt stmt, Local to, Type t, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_CAST_NULL, insn, str(index), Representation.local(m, to), writeType(t), methodId);
    }

    void writeStoreInstanceField(SootMethod m, Stmt stmt, SootField f, Local base, Local from, Session session) {
        writeInstanceField(m, stmt, f, base, from, session, STORE_INST_FIELD);
    }

    void writeLoadInstanceField(SootMethod m, Stmt stmt, SootField f, Local base, Local to, Session session) {
        writeInstanceField(m, stmt, f, base, to, session, LOAD_INST_FIELD);
    }

    private void writeInstanceField(SootMethod m, Stmt stmt, SootField f, Local base, Local var, Session session, PredicateFile storeInstField) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        String fieldId = writeField(f);
        _db.add(storeInstField, insn, str(index), Representation.local(m, var), Representation.local(m, base), fieldId, methodId);
    }

    void writeStoreStaticField(SootMethod m, Stmt stmt, SootField f, Local from, Session session) {
        writeStaticField(m, stmt, f, from, session, STORE_STATIC_FIELD);
    }

    void writeLoadStaticField(SootMethod m, Stmt stmt, SootField f, Local to, Session session) {
        writeStaticField(m, stmt, f, to, session, LOAD_STATIC_FIELD);
    }

    private void writeStaticField(SootMethod m, Stmt stmt, SootField f, Local var, Session session, PredicateFile staticFieldFacts) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        String fieldId = writeField(f);
        _db.add(staticFieldFacts, insn, str(index), Representation.local(m, var), fieldId, methodId);
    }

    void writeLoadArrayIndex(SootMethod m, Stmt stmt, Local base, Local to, Local arrIndex, Session session) {
        writeLoadOrStoreArrayIndex(m, stmt, base, to, arrIndex, session, LOAD_ARRAY_INDEX);
    }

    void writeStoreArrayIndex(SootMethod m, Stmt stmt, Local base, Local from, Local arrIndex, Session session) {
        writeLoadOrStoreArrayIndex(m, stmt, base, from, arrIndex, session, STORE_ARRAY_INDEX);
    }

    private void writeLoadOrStoreArrayIndex(SootMethod m, Stmt stmt, Local base, Local var, Local arrIndex, Session session, PredicateFile predicateFile) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(predicateFile, insn, str(index), Representation.local(m, var), Representation.local(m, base), methodId);

        if (arrIndex != null)
            _db.add(ARRAY_INSN_INDEX, insn, Representation.local(m, arrIndex));
    }

    private void writeApplicationClass(SootClass application) {
        _db.add(APP_CLASS, writeType(application));
    }

    String writeField(SootField f) {
        String fieldId = Representation.signature(f);
        _db.add(FIELD_SIGNATURE, fieldId, writeType(f.getDeclaringClass()), Representation.simpleName(f), writeType(f.getType()));
        if (f.getTag("VisibilityAnnotationTag") != null) {
            VisibilityAnnotationTag vTag = (VisibilityAnnotationTag) f.getTag("VisibilityAnnotationTag");
            for (AnnotationTag aTag : vTag.getAnnotations()) {
                _db.add(FIELD_ANNOTATION, fieldId, soot.coffi.Util.v().jimpleTypeOfFieldDescriptor(aTag.getType()).toQuotedString());
            }
        }
        return fieldId;
    }

    void writeFieldModifier(SootField f, String modifier) {
        String fieldId = writeField(f);
        _db.add(FIELD_MODIFIER, modifier, fieldId);
    }

    void writeClassModifier(SootClass c, String modifier) {
        writeClassModifier(c.getName(), modifier);
    }

    void writeMethodModifier(SootMethod m, String modifier) {
        String methodId = writeMethod(m);
        _db.add(METHOD_MODIFIER, modifier, methodId);
    }

    void writeReturn(SootMethod m, Stmt stmt, Local l, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(RETURN, insn, str(index), Representation.local(m, l), methodId);
    }

    void writeReturnVoid(SootMethod m, Stmt stmt, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(RETURN_VOID, insn, str(index), methodId);
    }

    // The return var of native methods is exceptional, in that it does not
    // correspond to a return instruction.
    void writeNativeReturnVar(SootMethod m) {
        String methodId = writeMethod(m);

        if (!(m.getReturnType() instanceof VoidType)) {
            String  var = Representation.nativeReturnVar(m);
            _db.add(NATIVE_RETURN_VAR, var, methodId);
            writeLocal(var, writeType(m.getReturnType()), methodId);
        }
    }

    void writeGoto(SootMethod m, GotoStmt stmt, Session session) {
        Unit to = stmt.getTarget();
        session.calcUnitNumber(stmt);
        int index = session.getUnitNumber(stmt);
        session.calcUnitNumber(to);
        int indexTo = session.getUnitNumber(to);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(GOTO, insn, str(index), str(indexTo), methodId);
    }

    /**
     * If
     */
    void writeIf(SootMethod m, IfStmt stmt, Session session) {
        Unit to = stmt.getTarget();
        // index was already computed earlier
        int index = session.getUnitNumber(stmt);
        session.calcUnitNumber(to);
        int indexTo = session.getUnitNumber(to);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        writeIf(insn, index, indexTo, methodId);

        Value condStmt = stmt.getCondition();
        if (condStmt instanceof ConditionExpr) {
            ConditionExpr condition = (ConditionExpr) condStmt;

            Local dummy = new JimpleLocal("tmp" + insn, BooleanType.v());
            writeDummyIfVar(insn, Representation.local(m, dummy));

            if (condition instanceof EqExpr)
                writeOperatorAt(insn, "==");
            else if (condition instanceof NeExpr)
                writeOperatorAt(insn, "!=");
            else if (condition instanceof GeExpr)
                writeOperatorAt(insn, ">=");
            else if (condition instanceof GtExpr)
                writeOperatorAt(insn, ">");
            else if (condition instanceof LeExpr)
                writeOperatorAt(insn, "<=");
            else if (condition instanceof LtExpr)
                writeOperatorAt(insn, "<");

            // TODO: create table entry for constants (?)
            if (condition.getOp1() instanceof Local) {
                Local op1 = (Local) condition.getOp1();
                writeIfVar(insn, L_OP, Representation.local(m, op1));
            }
            if (condition.getOp2() instanceof Local) {
                Local op2 = (Local) condition.getOp2();
                writeIfVar(insn, R_OP, Representation.local(m, op2));
            }
        }
    }

    void writeTableSwitch(SootMethod inMethod, TableSwitchStmt stmt, Session session) {
        int stmtIndex = session.getUnitNumber(stmt);

        Value v = writeImmediate(inMethod, stmt, stmt.getKey(), session);

        if(!(v instanceof Local))
            throw new RuntimeException("Unexpected key for TableSwitch statement " + v + " " + v.getClass());

        Local l = (Local) v;
        String insn = Representation.instruction(inMethod, stmt, stmtIndex);
        String methodId = writeMethod(inMethod);

        _db.add(TABLE_SWITCH, insn, str(stmtIndex), Representation.local(inMethod, l), methodId);

        for (int tgIndex = stmt.getLowIndex(), i = 0; tgIndex <= stmt.getHighIndex(); tgIndex++, i++) {
            session.calcUnitNumber(stmt.getTarget(i));
            int indexTo = session.getUnitNumber(stmt.getTarget(i));

            _db.add(TABLE_SWITCH_TARGET, insn, str(tgIndex), str(indexTo));
        }

        session.calcUnitNumber(stmt.getDefaultTarget());
        int defaultIndex = session.getUnitNumber(stmt.getDefaultTarget());

        _db.add(TABLE_SWITCH_DEFAULT, insn, str(defaultIndex));
    }

    void writeLookupSwitch(SootMethod inMethod, LookupSwitchStmt stmt, Session session) {
        int stmtIndex = session.getUnitNumber(stmt);

        Value v = writeImmediate(inMethod, stmt, stmt.getKey(), session);

        if(!(v instanceof Local))
            throw new RuntimeException("Unexpected key for TableSwitch statement " + v + " " + v.getClass());

        Local l = (Local) v;
        String insn = Representation.instruction(inMethod, stmt, stmtIndex);
        String methodId = writeMethod(inMethod);

        _db.add(LOOKUP_SWITCH, insn, str(stmtIndex), Representation.local(inMethod, l), methodId);

        for(int i = 0, end = stmt.getTargetCount(); i < end; i++) {
            int tgIndex = stmt.getLookupValue(i);
            session.calcUnitNumber(stmt.getTarget(i));
            int indexTo = session.getUnitNumber(stmt.getTarget(i));

            _db.add(LOOKUP_SWITCH_TARGET, insn, str(tgIndex), str(indexTo));
        }

        session.calcUnitNumber(stmt.getDefaultTarget());
        int defaultIndex = session.getUnitNumber(stmt.getDefaultTarget());

        _db.add(LOOKUP_SWITCH_DEFAULT, insn, str(defaultIndex));
    }

    void writeUnsupported(SootMethod m, Stmt stmt, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.unsupported(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(UNSUPPORTED_INSTRUCTION, insn, str(index), methodId);
    }

    /**
     * Throw statement
     */
    void writeThrow(SootMethod m, Unit unit, Local l, Session session) {
        int index = session.calcUnitNumber(unit);
        String insn = Representation.throwLocal(m, l, session);
        String methodId = writeMethod(m);

        _db.add(THROW, insn, str(index), Representation.local(m, l), methodId);
    }

    /**
     * Throw null
     */
    void writeThrowNull(SootMethod m, Stmt stmt, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(THROW_NULL, insn, str(index), methodId);
    }

    void writeExceptionHandlerPrevious(SootMethod m, Trap current, Trap previous, SessionCounter counter) {
        _db.add(EXCEPT_HANDLER_PREV, _rep.handler(m, current, counter), _rep.handler(m, previous, counter));
    }

    void writeExceptionHandler(SootMethod m, Trap handler, Session session) {
        SootClass exc = handler.getException();

        Local caught;
        {
            Unit handlerUnit = handler.getHandlerUnit();
            IdentityStmt stmt = (IdentityStmt) handlerUnit;
            Value left = stmt.getLeftOp();
            Value right = stmt.getRightOp();

            if (right instanceof CaughtExceptionRef && left instanceof Local) {
                caught = (Local) left;
            }
            else {
                throw new RuntimeException("Unexpected start of exception handler: " + handlerUnit);
            }
        }

        String insn = _rep.handler(m, handler, session);
        int handlerIndex = session.getUnitNumber(handler.getHandlerUnit());
        session.calcUnitNumber(handler.getBeginUnit());
        int beginIndex = session.getUnitNumber(handler.getBeginUnit());
        session.calcUnitNumber(handler.getEndUnit());
        int endIndex = session.getUnitNumber(handler.getEndUnit());
        writeExceptionHandler(insn, _rep.signature(m), handlerIndex, exc.getName(), Representation.local(m, caught), beginIndex, endIndex);
    }

    void writeThisVar(SootMethod m) {
        String methodId = writeMethod(m);
        String thisVar = Representation.thisVar(m);
        String type = writeType(m.getDeclaringClass());
        writeThisVar(methodId, thisVar, type);
    }

    void writeMethodDeclaresException(SootMethod m, SootClass exception) {
        writeMethodDeclaresException(writeMethod(m), writeType(exception));
    }

    void writeFormalParam(SootMethod m, int i) {
        String methodId = writeMethod(m);
        String var = Representation.param(m, i);
        String type = writeType(m.getParameterType(i));
        writeFormalParam(methodId, var, type, i);
    }

    void writeLocal(SootMethod m, Local l) {
        String local = Representation.local(m, l);
        Type type;

        if (_varTypeMap.containsKey(local))
            type = _varTypeMap.get(local);
        else {
            type = l.getType();
            _varTypeMap.put(local, type);
        }

        writeLocal(local, writeType(type), writeMethod(m));
    }

    private Local freshLocal(SootMethod inMethod, String basename, Type type, Session session) {
        String varname = basename + session.nextNumber(basename);
        Local l = new JimpleLocal(varname, type);
        writeLocal(inMethod, l);
        return l;
    }

    Local writeStringConstantExpression(SootMethod inMethod, Stmt stmt, StringConstant constant, Session session) {
        // introduce a new temporary variable
        Local l = freshLocal(inMethod, "$stringconstant", RefType.v("java.lang.String"), session);
        writeAssignStringConstant(inMethod, stmt, l, constant, session);
        return l;
    }

    Local writeNullExpression(SootMethod inMethod, Stmt stmt, Type type, Session session) {
        // introduce a new temporary variable
        Local l = freshLocal(inMethod, "$null", type, session);
        writeAssignNull(inMethod, stmt, l, session);
        return l;
    }

    Local writeNumConstantExpression(SootMethod inMethod, Stmt stmt, NumericConstant constant, Session session) {
        // introduce a new temporary variable
        Local l = freshLocal(inMethod, "$numconstant", constant.getType(), session);
        writeAssignNumConstant(inMethod, stmt, l, constant, session);
        return l;
    }

    Local writeClassConstantExpression(SootMethod inMethod, Stmt stmt, ClassConstant constant, Session session) {
        ClassConstantInfo info = new ClassConstantInfo(constant);
        // introduce a new temporary variable
        Local l = info.isMethodType ?
            freshLocal(inMethod, "$methodtypeconstant", RefType.v("java.lang.invoke.MethodType"), session) :
            freshLocal(inMethod, "$classconstant", RefType.v("java.lang.Class"), session);
        writeAssignClassConstant(inMethod, stmt, l, info, session);
        return l;
    }

    Local writeMethodHandleConstantExpression(SootMethod inMethod, Stmt stmt, MethodHandle constant, Session session) {
        // introduce a new temporary variable
        Local l = freshLocal(inMethod, "$mhandleconstant", RefType.v("java.lang.invoke.MethodHandle"), session);
        writeAssignMethodHandleConstant(inMethod, stmt, l, constant, session);
        return l;
    }

    private Value writeActualParam(SootMethod inMethod, Stmt stmt, InvokeExpr expr, Session session, Value v, int idx) {
        if (v instanceof StringConstant)
            return writeStringConstantExpression(inMethod, stmt, (StringConstant) v, session);
        else if (v instanceof ClassConstant)
            return writeClassConstantExpression(inMethod, stmt, (ClassConstant) v, session);
        else if (v instanceof NumericConstant)
            return writeNumConstantExpression(inMethod, stmt, (NumericConstant) v, session);
        else if (v instanceof MethodHandle)
            return writeMethodHandleConstantExpression(inMethod, stmt, (MethodHandle) v, session);
        else if (v instanceof NullConstant) {
            // Giving the type of the formal argument to be used in the creation of
            // temporary var for the actual argument (whose value is null).
            Type argType = expr.getMethodRef().parameterType(idx);
            return writeNullExpression(inMethod, stmt, argType, session);
        } else if (v instanceof Constant)
            throw new RuntimeException("Value has unknown constant type: " + v);
        else if (!(v instanceof JimpleLocal))
            System.err.println("Warning: value has unknown non-constant type: " + v.getClass().getName());
        return v;
    }

    private void writeActualParams(SootMethod inMethod, Stmt stmt, InvokeExpr expr, String invokeExprRepr, Session session) {
        for(int i = 0; i < expr.getArgCount(); i++) {
            Value v = writeActualParam(inMethod, stmt, expr, session, expr.getArg(i), i);
            if (v instanceof Local)
                writeActualParam(i, invokeExprRepr, Representation.local(inMethod, (Local)v));
            else
                throw new RuntimeException("Actual parameter is not a local: " + v + " " + v.getClass());
        }
        if (expr instanceof DynamicInvokeExpr) {
            DynamicInvokeExpr di = (DynamicInvokeExpr)expr;
            for (int j = 0; j < di.getBootstrapArgCount(); j++) {
                Value v = di.getBootstrapArg(j);
                if (v instanceof Constant) {
                    Value vConst = writeActualParam(inMethod, stmt, expr, session, v, j);
                    if (vConst instanceof Local) {
                        Local l = (Local) vConst;
                        _db.add(BOOTSTRAP_PARAMETER, str(j), invokeExprRepr, Representation.local(inMethod, l));
                    } else
                        throw new RuntimeException("Unknown actual parameter: " + v + " of type " + v.getClass().getName());
                } else
                    throw new RuntimeException("Found non-constant argument to bootstrap method: " + di);
            }
        }
    }

    void writeInvoke(SootMethod inMethod, Stmt stmt, Session session) {
        InvokeExpr expr = stmt.getInvokeExpr();
        writeInvokeHelper(inMethod, stmt, expr, session);
    }

    private String writeInvokeHelper(SootMethod inMethod, Stmt stmt, InvokeExpr expr, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.invoke(inMethod, expr, session);
        String methodId = writeMethod(inMethod);

        writeActualParams(inMethod, stmt, expr, insn, session);

        LineNumberTag tag = (LineNumberTag) stmt.getTag("LineNumberTag");
        if (tag != null) {
            _db.add(METHOD_INV_LINE, insn, str(tag.getLineNumber()));
        }

        if (expr instanceof StaticInvokeExpr) {
            _db.add(STATIC_METHOD_INV, insn, str(index), _rep.signature(expr.getMethod()), methodId);
        }
        else if (expr instanceof VirtualInvokeExpr || expr instanceof InterfaceInvokeExpr) {
            _db.add(VIRTUAL_METHOD_INV, insn, str(index), _rep.signature(expr.getMethod()), Representation.local(inMethod, (Local) ((InstanceInvokeExpr) expr).getBase()), methodId);
        }
        else if (expr instanceof SpecialInvokeExpr) {
            _db.add(SPECIAL_METHOD_INV, insn, str(index), _rep.signature(expr.getMethod()), Representation.local(inMethod, (Local) ((InstanceInvokeExpr) expr).getBase()), methodId);
        }
        else if (expr instanceof DynamicInvokeExpr) {
            writeDynamicInvoke((DynamicInvokeExpr)expr, index, insn, methodId);
        }
        else {
            throw new RuntimeException("Cannot handle invoke expr: " + expr);
        }

        return insn;
    }

    private String getBootstrapSig(DynamicInvokeExpr di) {
        SootMethodRef bootstrapMeth = di.getBootstrapMethodRef();
        if (bootstrapMeth.declaringClass().isPhantom()) {
            String bootstrapSig = bootstrapMeth.toString();
            if (_reportPhantoms)
                System.out.println("Bootstrap method is phantom: " + bootstrapSig);
            _db.add(PHANTOM_METHOD, bootstrapSig);
            return bootstrapSig;
        } else
            return _rep.signature(bootstrapMeth.resolve());
    }

    private void writeDynamicInvoke(DynamicInvokeExpr di, int index, String insn, String methodId) {
        SootMethodRef dynInfo = di.getMethodRef();
        SigInfo dynSig = new SigInfo(dynInfo);
        for (int pIdx = 0; pIdx < dynSig.arity; pIdx++)
            writeInvokedynamicParameterType(insn, pIdx, dynInfo.parameterType(pIdx).toString());
        writeInvokedynamic(insn, index, getBootstrapSig(di), dynInfo.name(), dynSig.retType, dynSig.arity, dynSig.paramTypes, di.getHandleTag(), methodId);
    }

    private Value writeImmediate(SootMethod inMethod, Stmt stmt, Value v, Session session) {
        if (v instanceof StringConstant)
            v = writeStringConstantExpression(inMethod, stmt, (StringConstant) v, session);
        else if (v instanceof ClassConstant)
            v = writeClassConstantExpression(inMethod, stmt, (ClassConstant) v, session);
        else if (v instanceof NumericConstant)
            v = writeNumConstantExpression(inMethod, stmt, (NumericConstant) v, session);

        return v;
    }

    void writeAssignBinop(SootMethod m, AssignStmt stmt, Local left, BinopExpr right, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        writeAssignBinop(insn, index, Representation.local(m, left), methodId);

        if (right instanceof AddExpr)
                writeOperatorAt(insn, "+");
        else if (right instanceof SubExpr)
                writeOperatorAt(insn, "-");
        else if (right instanceof MulExpr)
                writeOperatorAt(insn, "*");
        else if (right instanceof DivExpr)
                writeOperatorAt(insn, "/");
        else if (right instanceof RemExpr)
                writeOperatorAt(insn, "%");
        else if (right instanceof AndExpr)
                writeOperatorAt(insn, "&");
        else if (right instanceof OrExpr)
                writeOperatorAt(insn, "|");
        else if (right instanceof XorExpr)
                writeOperatorAt(insn, "^");
        else if (right instanceof ShlExpr)
                writeOperatorAt(insn, "<<");
        else if (right instanceof ShrExpr)
                writeOperatorAt(insn, ">>");
        else if (right instanceof UshrExpr)
                writeOperatorAt(insn, ">>>");


        if (right.getOp1() instanceof Local) {
            Local op1 = (Local) right.getOp1();
            writeAssignOperFrom(insn, L_OP, Representation.local(m, op1));
        }

        if (right.getOp2() instanceof Local) {
            Local op2 = (Local) right.getOp2();
            writeAssignOperFrom(insn, R_OP, Representation.local(m, op2));
        }
    }

    void writeAssignUnop(SootMethod m, AssignStmt stmt, Local left, UnopExpr right, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        writeAssignUnop(insn, index, Representation.local(m, left), methodId);
        writeOperatorAt(insn, "-");

        if (right.getOp() instanceof Local) {
            Local op = (Local) right.getOp();
            writeAssignOperFrom(insn, L_OP, Representation.local(m, op));
        }
    }

    void writeAssignInstanceOf(SootMethod m, AssignStmt stmt, Local to, Local from, Type t, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_INSTANCE_OF, insn, str(index), Representation.local(m, from), Representation.local(m, to), writeType(t), methodId);
    }

    void writeAssignPhantomInvoke(SootMethod m, AssignStmt stmt, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(ASSIGN_PHANTOM_INVOKE, insn, str(index), methodId);
    }

    void writeBreakpointStmt(SootMethod m, Stmt stmt, Session session) {
        int index = session.calcUnitNumber(stmt);
        String insn = Representation.instruction(m, stmt, index);
        String methodId = writeMethod(m);

        _db.add(BREAKPOINT_STMT, insn, str(index), methodId);
    }

    void writeFieldInitialValue(SootField f) {
        String fieldId = Representation.signature(f);
        List<Tag> tagList = f.getTags();
        for (Tag tag : tagList)
            if (tag instanceof ConstantValueTag) {
                String val = ((ConstantValueTag)tag).getConstant().toString();
                _db.add(FIELD_INITIAL_VALUE, fieldId, val);
                // Put constant in appropriate "raw" input facts.
                if ((tag instanceof IntegerConstantValueTag) ||
                    (tag instanceof DoubleConstantValueTag) ||
                    (tag instanceof LongConstantValueTag) ||
                    (tag instanceof FloatConstantValueTag)) {
                    // Trim last non-digit qualifier (e.g. 'L' in long constants).
                    int len = val.length();
                    if (!Character.isDigit(val.charAt(len-1)))
                        val = val.substring(0, len-1);
                    _db.add(NUM_CONSTANT_RAW, val);
                } else if (tag instanceof StringConstantValueTag) {
                    writeStringConstant(val);
                } else
                    System.err.println("Unsupported field tag " + tag.getClass());
            }
    }

    public void writePreliminaryFacts(Collection<SootClass> classes, BasicJavaSupport java, SootParameters sootParameters) {
        classes.stream().filter(SootClass::isApplicationClass).forEachOrdered(this::writeApplicationClass);
        writePreliminaryFacts(java, sootParameters);
    }

    boolean checkAndRegisterPhantom(Object phantom) {
        if (seenPhantoms.contains(phantom))
            return true;

        seenPhantoms.add(phantom);
        return false;
    }

    static class SigInfo {
        public int arity;
        public String retType;
        public String paramTypes;
        public SigInfo(SootMethodRef ref) {
            this.arity = ref.parameterTypes().size();
            this.retType = ref.returnType().toString();

            StringBuffer dpTypes = new StringBuffer("(");
            ref.parameterTypes().forEach(p -> dpTypes.append(p.toString()));
            this.paramTypes = dpTypes.append(")").toString();
        }
    }
}
