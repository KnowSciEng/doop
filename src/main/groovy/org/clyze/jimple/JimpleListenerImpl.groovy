package org.clyze.jimple

import groovy.transform.CompileStatic
import org.antlr.v4.runtime.ANTLRFileStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ErrorNode
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.clyze.persistent.model.Element
import org.clyze.persistent.model.Position
import org.clyze.persistent.model.doop.*
import org.clyze.persistent.model.doop.Class as Klass
import org.codehaus.groovy.runtime.StackTraceUtils

import static org.clyze.jimple.JimpleParser.*

@CompileStatic
class JimpleListenerImpl extends JimpleBaseListener {

	String filename
	List<Variable> pending
	Map varTypes = [:]
	Map heapCounters
	Klass klass
	Method method
	Map methodInvoCounters
	Map values = [:]
	boolean inDecl
	boolean inInterface

	BasicMetadata metadata = new BasicMetadata()

	Closure processor

	JimpleListenerImpl(String filename, Closure processor = null) {
		this.filename = filename
		if (processor)
			this.processor = processor
		else
			this.processor = { Element e ->
				if (e instanceof Klass) metadata.classes << (e as Klass)
				else if (e instanceof Field) metadata.fields << (e as Field)
				else if (e instanceof Method) metadata.methods << (e as Method)
				else if (e instanceof Variable) metadata.variables << (e as Variable)
				else if (e instanceof Usage) metadata.usages << (e as Usage)
				else if (e instanceof HeapAllocation) metadata.heapAllocations << (e as HeapAllocation)
				else if (e instanceof MethodInvocation) metadata.invocations << (e as MethodInvocation)
			}
	}

	void enterKlass(KlassContext ctx) {
		def id = ctx.IDENTIFIER(0)
		def line = id.symbol.line
		def startCol = id.symbol.charPositionInLine + 1
		def qualifiedName = ctx.IDENTIFIER(0).text
		def position = new Position(line, line, startCol, startCol + qualifiedName.length())
		def packageName = getPackageName(qualifiedName)
		def className = getClassName(qualifiedName)
		inInterface = hasToken(ctx, "interface")

		klass = new Klass(
				position,
				filename,
				className,
				packageName,
				qualifiedName,
				inInterface,
				ctx.modifier().any() { hasToken(it, "enum") },
				ctx.modifier().any() { hasToken(it, "static") },
				false, //isInner, missing?
				false, //isAnonymous, missing?
				hasToken(ctx, "abstract")
		)
		processor.call klass

		addTypeUsage(ctx.IDENTIFIER(1))
		gatherIdentifiers(ctx.identifierList()).each { addTypeUsage it }
	}

	void exitField(FieldContext ctx) {
		def id = ctx.IDENTIFIER(1)
		def line = id.symbol.line
		def startCol = id.symbol.charPositionInLine + 1
		def type = ctx.IDENTIFIER(0).text + (hasToken(ctx, "[]") ? "[]" : "")
		def name = ctx.IDENTIFIER(1).text
		def position = new Position(line, line, startCol, startCol + name.length())

		processor.call new Field(
				position,
				filename,
				name,
				"<${klass.doopId}: $type $name>", //doopId
				type,
				klass.doopId, //declaringClassDoopId
				ctx.modifier().any() { hasToken(it, "static") }
		)
	}

	void enterMethod(MethodContext ctx) {
		def id = ctx.IDENTIFIER(1)
		def line = id.symbol.line
		def startCol = id.symbol.charPositionInLine + 1
		def retType = ctx.IDENTIFIER(0).text
		def name = ctx.IDENTIFIER(1).text
		def position = new Position(line, line, startCol, startCol + name.length())

		def paramTypes = gatherIdentifiers(ctx.identifierList())
		def paramTypeNames = paramTypes.collect { it.text }
		def params = paramTypeNames.join(",")

		def endline = ctx.methodBody() ? getLastToken(ctx.methodBody()).symbol.line : line

		method = new Method(
				position,
				filename,
				name,
				klass.doopId, //declaringClassDoopId
				retType,
				"<${klass.doopId}: $retType $name($params)>", //doopId
				null, //params, TODO
				paramTypeNames as String[],
				ctx.modifier().any() { hasToken(it, "static") },
				inInterface,
				ctx.modifier()?.any { it.text == "abstract" },
				ctx.modifier()?.any { it.text == "native" },
				new Position(line, endline, 0, 0)
		)		

		heapCounters = [:].withDefault { 0 }
		methodInvoCounters = [:].withDefault { 0 }
		processor.call method

		addTypeUsage(ctx.IDENTIFIER(0))
		paramTypes.each { addTypeUsage it }
		gatherIdentifiers(ctx.throwsExceptions()?.identifierList()).each { addTypeUsage it }
	}

	void exitIdentifierList(IdentifierListContext ctx) {
		if (inDecl) processor.call var(ctx.IDENTIFIER(), true)
	}

	void enterDeclarationStmt(DeclarationStmtContext ctx) {
		inDecl = true
		pending = []
	}

	void exitDeclarationStmt(DeclarationStmtContext ctx) {
		inDecl = false
		def type = ctx.IDENTIFIER().text
		pending.each { v ->
			v.type = type
			varTypes[v.doopId] = type
		}
		addTypeUsage(ctx.IDENTIFIER())
	}

	void exitComplexAssignmentStmt(ComplexAssignmentStmtContext ctx) {
		if (ctx.IDENTIFIER())
			addVarUsage(ctx.IDENTIFIER(), UsageKind.DATA_READ)

		if (ctx.fieldSig())
			processor.call fieldUsage(ctx.fieldSig(), UsageKind.DATA_WRITE)

		(0..1).each {
			if (ctx.value(it)?.IDENTIFIER())
				addVarUsage(ctx.value(it).IDENTIFIER(), UsageKind.DATA_READ)
		}
	}

	void exitAssignmentStmt(AssignmentStmtContext ctx) {
		addVarUsage(ctx.IDENTIFIER(0), UsageKind.DATA_WRITE)

		// @parameterN and @this
		if (ctx.IDENTIFIER(2)) {
			def v = var(ctx.IDENTIFIER(1), true)
			v.type = ctx.IDENTIFIER(2).text
			pending.pop()
			processor.call v
			addTypeUsage(ctx.IDENTIFIER(2))
		} else if (ctx.IDENTIFIER(1))
			addVarUsage(ctx.IDENTIFIER(1), UsageKind.DATA_READ)

		// Cast Assignment
		if (hasToken(ctx, "(") && !hasToken(ctx, "Phi"))
			addTypeUsage(ctx.IDENTIFIER(1))
		// Read field
		else if (ctx.fieldSig())
			processor.call fieldUsage(ctx.fieldSig(), UsageKind.DATA_READ)
		// Phi
		else if (hasToken(ctx, "Phi"))
			gatherIdentifiers(ctx.identifierList()).each { addVarUsage(it, UsageKind.DATA_READ) }
	}

	void exitReturnStmt(ReturnStmtContext ctx) {
		if (ctx.value()?.IDENTIFIER())
			addVarUsage(ctx.value().IDENTIFIER(), UsageKind.DATA_READ)
	}

	void exitAllocationStmt(AllocationStmtContext ctx) {
		addVarUsage(ctx.IDENTIFIER(0), UsageKind.DATA_WRITE)
		ctx.value().each {
			if (it.IDENTIFIER())
				addVarUsage(it.IDENTIFIER(), UsageKind.DATA_READ)
		}

		def typeId = ctx.IDENTIFIER(1)
		def line = typeId.symbol.line
		def type = typeId.text
		def lastToken = getLastToken(ctx)
		int startCol = 0, endCol = 0
		TerminalNode newToken
		boolean isArray = false

		if ((newToken = findToken(ctx, "new"))) {
			startCol = newToken.symbol.charPositionInLine + 1
			endCol = typeId.symbol.charPositionInLine + 1 + type.length()
		} else if ((newToken = findToken(ctx, "newarray"))) {
			type = "$type[]" as String
			startCol = newToken.symbol.charPositionInLine + 1
			endCol = lastToken.symbol.charPositionInLine + 2
			isArray = true
		} else if ((newToken = findToken(ctx, "newmultiarray"))) {
			def lastIsEmpty = lastToken.text == "[]"
			def dimensions = ctx.value().size() + (lastIsEmpty ? 1 : 0)
			type = type + (1..dimensions).collect { "[]" }.join("")
			startCol = newToken.symbol.charPositionInLine + 1
			endCol = lastToken.symbol.charPositionInLine + (lastIsEmpty ? 3 : 2)
			isArray = true
		}

		def c = heapCounters[type] as int
		heapCounters[type] = c + 1

		processor.call new HeapAllocation(
				new Position(line, line, startCol, endCol),
				filename,
				"${method.doopId}/new $type/$c", //doopId
				type,
				method.doopId, //allocatingMethodDoopId
				false, //inIIB
				isArray)
	}

	void exitInvokeStmt(InvokeStmtContext ctx) {
		if (ctx.IDENTIFIER(0)) {
			if (hasToken(ctx, "=")) {
				addVarUsage(ctx.IDENTIFIER(0), UsageKind.DATA_WRITE)
				if (ctx.IDENTIFIER(1))
					addVarUsage(ctx.IDENTIFIER(1), UsageKind.DATA_READ)
			} else
				addVarUsage(ctx.IDENTIFIER(0), UsageKind.DATA_READ)
		}

		def methodClassId = ctx.methodSig().IDENTIFIER(0)
		def methodClass = methodClassId.text
		def methodName = ctx.methodSig().IDENTIFIER(2).text

		def line = methodClassId.symbol.line
		def startCol = methodClassId.symbol.charPositionInLine
		def endCol = getLastToken(ctx.methodSig()).symbol.charPositionInLine + 2

		String gDoopId = null
		if (ctx.dynamicMethodSig())
			gDoopId = dynamicInvokeMiddlePart(ctx)

		if (!gDoopId) {
			def c = methodInvoCounters["$methodClass|$methodName"] as int
			methodInvoCounters["$methodClass|$methodName"] = c + 1
			gDoopId = "${method.doopId}/${methodClass}.$methodName/$c"
		}

		processor.call new MethodInvocation(
				new Position(line, line, startCol, endCol),
				filename,
				methodName,
				gDoopId, //doopId
				method.doopId, //invokingMethodDoopId
				false //inIIB
		)
	}

	// This follows how Representation.dynamicInvokeIdMiddle() works.
	String dynamicInvokeMiddlePart(InvokeStmtContext ctx) {
		def bootName = "${ctx.methodSig().IDENTIFIER(0).text}.${ctx.methodSig().IDENTIFIER(2).text}"
		def invoId = null
		if (bootName == "java.lang.invoke.LambdaMetafactory.metafactory" ||
			bootName == "java.lang.invoke.LambdaMetafactory.altMetafactory") {
			def bootArgs = values[ctx.bootValueList()?.valueList()] as List
			if (!bootArgs) {
				println("Warning: metafactory invokedynamic with null bootArgs in $filename")
			} else if (bootArgs.size() > 1) {
				def v = (bootArgs[1] as ValueContext).methodSig()
				if (v) {
					def declClass = v.IDENTIFIER(0).text
					def mName = v.IDENTIFIER(2).text
					invoId = DynamicMethodInvocation.genId(declClass, mName)
				} else {
					println("Warning: metafactory invokedynamic, unknown boot argument 2: $bootArgs in $filename")
				}
			} else {
				println("Warning: metafactory invokedynamic, unknown boot arguments of arity ${bootArgs.size()} in $filename")
			}
		}

		if (!invoId) {
			def dynamicName = ctx.STRING().text
			invoId = DynamicMethodInvocation.genericId(bootName, dynamicName)
		}

		def c = methodInvoCounters[invoId] as int
		methodInvoCounters[invoId] = c + 1
		return "${method.doopId}/${invoId}/$c"
	}

	void exitMethodSig(MethodSigContext ctx) {
		addTypeUsage(ctx.IDENTIFIER(0))
		addTypeUsage(ctx.IDENTIFIER(1))
		gatherIdentifiers(ctx.identifierList()).each { addTypeUsage it }
	}

	void exitValueList(ValueListContext ctx) {
		def list = (values[ctx.valueList()] ?: []) as List
		values[ctx] = (list << ctx.value())

		if (ctx.value().IDENTIFIER())
			addVarUsage(ctx.value().IDENTIFIER(), UsageKind.DATA_READ)
	}

	void exitJumpStmt(JumpStmtContext ctx) {
		(0..1).each {
			if (ctx.value(it)?.IDENTIFIER())
				addVarUsage(ctx.value(it).IDENTIFIER(), UsageKind.DATA_READ)
		}
	}

	void visitErrorNode(ErrorNode node) {
		throw new RuntimeException("Parsing error in $filename")
	}


	Variable var(TerminalNode id, boolean isLocal) {
		def line = id.symbol.line
		def startCol = id.symbol.charPositionInLine + 1
		def name = id.text

		def v = new Variable(
				new Position(line, line, startCol, startCol + name.length()),
				filename,
				name,
				"${method.doopId}/$name", //doopId
				null, //type, provided later
				method.doopId, //declaringMethodDoopId
				isLocal,
				!isLocal,
				false //inIIB
		)

		if (varTypes[v.doopId])
			v.type = varTypes[v.doopId] as String
		else
			pending.push(v)
		return v
	}

	void addVarUsage(TerminalNode id, UsageKind kind) {
		def line = id.symbol.line
		def startCol = id.symbol.charPositionInLine + 1
		def name = id.text

		processor.call new Usage(
				new Position(line, line, startCol, startCol + name.length()),
				filename,
				"${method.doopId}/$name", //doopId
				kind
		)
	}

	Usage fieldUsage(FieldSigContext ctx, UsageKind kind) {
		def klassStr = ctx.IDENTIFIER(0).text
		def type = ctx.IDENTIFIER(1).text
		def name = ctx.IDENTIFIER(2).text

		def line = ctx.IDENTIFIER(0).symbol.line
		def startCol = ctx.IDENTIFIER(0).symbol.charPositionInLine
		def endCol = getLastToken(ctx).symbol.charPositionInLine + 1

		new Usage(
				new Position(line, line, startCol, endCol),
				filename,
				"<$klassStr: $type $name>", //doopId
				kind
		)
	}

	void addTypeUsage(TerminalNode id) {
		if (!id) return

		def line = id.symbol.line
		def startCol = id.symbol.charPositionInLine + 1
		def name = id.text

		if (name == "void") return

		processor.call new Usage(
				new Position(line, line, startCol, startCol + name.length()),
				filename,
				name, //doopId
				UsageKind.TYPE
		)
	}

	List<TerminalNode> gatherIdentifiers(IdentifierListContext ctx) {
		if (ctx == null) return []
		return gatherIdentifiers(ctx.identifierList()) + [ctx.IDENTIFIER()]
	}

	static TerminalNode findToken(ParserRuleContext ctx, String token) {
		for (def i = 0; i < ctx.childCount; i++)
			if (ctx.getChild(i) instanceof TerminalNode && (ctx.getChild(i) as TerminalNode).text == token)
				return ctx.getChild(i) as TerminalNode
		return null
	}

	static boolean hasToken(ParserRuleContext ctx, String token) { findToken(ctx, token) != null }

	static TerminalNode getLastToken(ParserRuleContext ctx) {
		TerminalNode last = null
		for (def i = 0; i < ctx.getChildCount(); i++)
			if (ctx.getChild(i) instanceof TerminalNode)
				last = ctx.getChild(i) as TerminalNode
		return last
	}

	static String getPackageName(String qualifiedName) {
		def i = qualifiedName.lastIndexOf(".")
		i >= 0 ? qualifiedName[0..(i - 1)] : ''
	}

	static String getClassName(String qualifiedName) {
		def i = qualifiedName.lastIndexOf(".")
		i >= 0 ? qualifiedName[(i + 1)..-1] : qualifiedName
	}

	static void parseJimple(String filename, String baseDir, Closure processor) {
		// filename: XYZ/abc/def/Foo.jimple
		if (!baseDir.endsWith("/")) baseDir += "/"
		// abc/def/Foo.jimple
		def listener = new JimpleListenerImpl(filename - baseDir, processor)
		def parser = new JimpleParser(new CommonTokenStream(new JimpleLexer(new ANTLRFileStream(filename))))
		try {
			ParseTreeWalker.DEFAULT.walk(listener, parser.program())
		} catch (all) {
			all = StackTraceUtils.deepSanitize all
			throw StackTraceUtils.deepSanitize(new Throwable("Jimple File: $filename", all))
		}
	}
}
