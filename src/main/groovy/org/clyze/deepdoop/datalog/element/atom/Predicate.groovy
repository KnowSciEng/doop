package org.clyze.deepdoop.datalog.element.atom

import groovy.transform.Canonical
import org.clyze.deepdoop.actions.IVisitor
import org.clyze.deepdoop.datalog.expr.IExpr
import org.clyze.deepdoop.datalog.expr.VariableExpr

@Canonical
class Predicate implements IAtom {

	String name
	String stage
	List<IExpr> exprs

	int getArity() { exprs.size() }

	IAtom newAtom(String stage, List<VariableExpr> vars) {
		newAlias(name, stage, vars)
	}

	IAtom newAlias(String name, String stage, List<VariableExpr> vars) {
		assert arity == vars.size()
		return new Predicate(name, stage, [] + vars)
	}

	def <T> T accept(IVisitor<T> v) { v.visit(this) }
}
