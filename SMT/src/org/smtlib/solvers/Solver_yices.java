/*
 * This file is part of the SMT project.
 * Copyright 2010 David R. Cok
 * Created August 2010
 */
package org.smtlib.solvers;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.smtlib.*;
import org.smtlib.ICommand.Ideclare_fun;
import org.smtlib.ICommand.Ideclare_sort;
import org.smtlib.ICommand.Idefine_fun;
import org.smtlib.ICommand.Idefine_sort;
import org.smtlib.IExpr.IAsIdentifier;
import org.smtlib.IExpr.IAttribute;
import org.smtlib.IExpr.IAttributeValue;
import org.smtlib.IExpr.IAttributedExpr;
import org.smtlib.IExpr.IBinaryLiteral;
import org.smtlib.IExpr.IBinding;
import org.smtlib.IExpr.IDecimal;
import org.smtlib.IExpr.IDeclaration;
import org.smtlib.IExpr.IError;
import org.smtlib.IExpr.IExists;
import org.smtlib.IExpr.IFcnExpr;
import org.smtlib.IExpr.IForall;
import org.smtlib.IExpr.IHexLiteral;
import org.smtlib.IExpr.IKeyword;
import org.smtlib.IExpr.ILet;
import org.smtlib.IExpr.INumeral;
import org.smtlib.IExpr.IParameterizedIdentifier;
import org.smtlib.IExpr.IQualifiedIdentifier;
import org.smtlib.IExpr.IStringLiteral;
import org.smtlib.IExpr.ISymbol;

// FIXME - in some commands, like assert, push, pop, the effect in solver_test happens even if the effect in the 
// solver itself causes an error, putting the two out of synch; also, push and pop can happen partially
/** This class is the adapter for the Yices SMT solver */
public class Solver_yices extends Solver_test implements ISolver {
	/** This holds the command-line arguments used to launch the solver;
	 * the path to the executable is inserted in cmds[0]. */
	String cmds[] = new String[]{"","-i"};
	
	/** Holds the driver for external processes */
	private SolverProcess solverProcess;
	
	/** The string that indicates an Error in the solver reply */
	static public final String errorIndication = "Error";

	/** Records the values of options */
	protected Map<String,IAttributeValue> options = new HashMap<String,IAttributeValue>();
	{ 
		options.putAll(Utils.defaults);
	}
	
	/** Creates but does not start a solver instance */
	public Solver_yices(SMT.Configuration smtConfig, String executable) {
		super(smtConfig,"");
		cmds[0] = executable;
		solverProcess = new SolverProcess(cmds,"yices > ","solver.out.yices");
		try {
			solverProcess.log = new FileWriter("solver.out.yices");
		} catch (IOException e) {
			smtConfig.log.logError("Failed to create solver log file for yices: " + e);
		}
	}
	
	@Override
	public IResponse start() {
		super.start();
		try {
			solverProcess.start();
			if (smtConfig.verbose != 0) smtConfig.log.logDiag("Started yices " + (solverProcess!=null));
			return smtConfig.responseFactory.success();
		} catch (Exception e) {
			return smtConfig.responseFactory.error("Failed to start process " + cmds[0] + " : " + e.getMessage());
		}
	}

	// FIXME - are we capturing errors from the solver?
	
	@Override
	public IResponse exit() {
		try {
			String s = solverProcess.sendAndListen("(exit)\n");
			solverProcess.exit();
			if (s.contains(errorIndication)) {
				return smtConfig.responseFactory.error(s);
			}
			if (smtConfig.verbose != 0) smtConfig.log.logDiag("Ended yices ");
			return smtConfig.responseFactory.success_exit();
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Failed to exit Yices process: " + e.getMessage());
		}
	}

	@Override
	public IResponse assertExpr(IExpr sexpr) {
		try {
			IResponse status = super.assertExpr(sexpr);
			if (!status.isOK()) return status;

			String response = solverProcess.sendAndListen("(assert+ ",translate(sexpr)," )\n");
			if (response.contains(errorIndication)) {
				return smtConfig.responseFactory.error(response);
			}
			return status;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Yices assert command failed: " + e.getMessage());
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Yices assert command failed: " + e.getMessage());
		}

	}

	@Override
	public IResponse check_sat() {
		IResponse res = super.check_sat();
		if (res.isError()) return res;

		try {
			String s = solverProcess.sendAndListen("(check)\r\n");
			if (s.contains(errorIndication)) {
				return smtConfig.responseFactory.error(s);
			}
			//System.out.println("HEARD: " + s);
			if (s.contains("unsat")) res = smtConfig.responseFactory.unsat();
			else if (s.contains("sat")) res = smtConfig.responseFactory.sat();
			else res = smtConfig.responseFactory.unknown();
			checkSatStatus = res;
		} catch (IOException e) {
			res = smtConfig.responseFactory.error("Failed to check-sat");
		}
		return res;
	}

	@Override
	public IResponse pop(int number) {
		try {
			IResponse status = super.pop(number);
			if (status.isError()) return status;
			while (number-- > 0) {
				String response = solverProcess.sendAndListen("(pop)\n");
				if (response.contains(errorIndication)) {
					return smtConfig.responseFactory.error(response);
				}
			}
			return smtConfig.responseFactory.success();
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Yices pop command failed: " + e.getMessage());
		}
	}

	@Override
	public IResponse push(int number) {
		try {
			IResponse status = super.push(number);
			if (status.isError()) return status;
			while (number-- > 0) {
				String response = solverProcess.sendAndListen("(push)\n");
				if (response.contains(errorIndication)) {
					return smtConfig.responseFactory.error(response);
				}
			}
			return smtConfig.responseFactory.success();
		} catch (IOException e) {
			return smtConfig.responseFactory.error("push command failed: " + e.getMessage());
		}
	}

	@Override
	public IResponse set_logic(String logicName, /*@Nullable*/ IPos pos) {
		try {
			boolean lSet = logicSet;
			IResponse status = super.set_logic(logicName,pos);
			if (!status.isOK()) return status;

			// FIXME - discrimninate among logics

			if (lSet) {
				if (!smtConfig.relax) return smtConfig.responseFactory.error("Logic is already set");
				String response = solverProcess.sendAndListen("(reset)\n");
				if (response.contains(errorIndication)) {
					return smtConfig.responseFactory.error(response,pos);
				}
			}
			return status;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("set_logic command failed: " + e.getMessage());
		}


	}

	@Override
	public IResponse set_option(IKeyword option, IAttributeValue value) {
		return super.set_option(option,value); // FIXME - send to yices?
	}

	@Override
	public IResponse get_option(IKeyword option) {
		return super.get_option(option); // FIXME - does yices know this
	}

	@Override
	public IResponse get_info(IKeyword key) {
		String option = key.value();
		if (":error-behavior".equals(option)) {
			return smtConfig.responseFactory.continued_execution(); // FIXME - is this true?
		} else if (":status".equals(option)) {
			return checkSatStatus==null ? smtConfig.responseFactory.unsupported() : checkSatStatus; 
		} else if (":all-statistics".equals(option)) {
			return smtConfig.responseFactory.unsupported(); // FIXME
		} else if (":reason-unknown".equals(option)) {
			return smtConfig.responseFactory.unsupported(); // FIXME
		} else if (":authors".equals(option)) {
			return smtConfig.responseFactory.stringLiteral(Utils.AUTHORS_VALUE);
		} else if (":version".equals(option)) {
			return smtConfig.responseFactory.stringLiteral(Utils.VERSION_VALUE);
		} else if (":name".equals(option)) {
			return smtConfig.responseFactory.stringLiteral("yices");
		} else {
			return smtConfig.responseFactory.unsupported();
		}
	}
	
	@Override
	public IResponse declare_fun(Ideclare_fun cmd) {
		try {
			IResponse status = super.declare_fun(cmd);
			if (!status.isOK()) return status;

			String name = translate(cmd.symbol());
			String yicescmd;
			if (cmd.argSorts().size() == 0) {
				yicescmd = "(define " + name + "::" + translate(cmd.resultSort()) + ")\n";
			} else {
				yicescmd = "(define " + name + "::(->";
				for (ISort s: cmd.argSorts()) {
					yicescmd = yicescmd + " " + translate(s);
				}
				yicescmd = yicescmd + " " + translate(cmd.resultSort()) + "))\n";
				
			}
			String response = solverProcess.sendAndListen(yicescmd);
			if (response.contains(errorIndication)) {
				return smtConfig.responseFactory.error(response);
			}
			return status;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("declare-fun command failed: " + e.getMessage());
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("declare-fun command failed: " + e.getMessage());
		}
	}

	@Override
	public IResponse define_fun(Idefine_fun cmd) {
		try {
			IResponse status = super.define_fun(cmd);
			if (!status.isOK()) return status;
			if (false) throw new IOException();

//			String name = cmd.identifier().toString(); // FIXME need proper encoding
//			String response = solverProcess.sendAndListen("(define " + name + "::bool)\n");
//			if (response.contains(errorIndication)) {
//				return smtConfig.responseFactory.error(response);
//			}
			return status;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("assert command failed: " + e.getMessage());
		}

	}

	@Override
	public IResponse declare_sort(Ideclare_sort cmd) {
		try {
			IResponse status = super.declare_sort(cmd);
			if (!status.isOK()) return status;
			if (false) throw new IOException();
			
			// FIXME - Yices does not seem to allow creating arbitrary new types
			// Besides Yices uses structural equivalence.

//			String name = cmd.identifier().toString(); // FIXME need proper encoding
//			solverProcess.sendAndListen("(define " + name + "::bool)\n");
//			if (response.contains(errorIndication)) {
//				return smtConfig.responseFactory.error(response);
//			}
			return status;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Yices declare-sort command failed: " + e.getMessage());
		}

	}

	@Override
	public IResponse define_sort(Idefine_sort cmd) {
		try {
			IResponse status = super.define_sort(cmd);
			if (!status.isOK()) return status;
			if (false) throw new IOException();
//			String name = cmd.identifier().toString(); // FIXME need proper encoding
//			solverProcess.sendAndListen("(define " + name + "::bool)\n");
//			if (response.contains(errorIndication)) {
//				return smtConfig.responseFactory.error(response);
//			}
			return status;
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Yices define-sort command failed: " + e.getMessage());
		}

	}

	@Override 
	public IResponse get_proof() {
		IResponse status = super.get_proof();
		if (status.isError()) return status;
		try {
			String response = solverProcess.sendAndListen("(get-proof)\n");
			if (response.contains(errorIndication)) {
				return smtConfig.responseFactory.error(response);
			}
			return smtConfig.responseFactory.unsupported(); // FIXME - need to return the proof
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override 
	public IResponse get_unsat_core() {
		IResponse status = super.get_unsat_core();
		if (status.isError()) return status;
		try {
			String response = solverProcess.sendAndListen("(get-unsat-core)\n");
			if (response.contains(errorIndication)) {
				return smtConfig.responseFactory.error(response);
			}
			return smtConfig.responseFactory.unsupported(); // FIXME - need to return the unsat core
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override 
	public IResponse get_assignment() {
		IResponse status = super.get_assignment();
		if (status.isError()) return status;
		try {
			String response = solverProcess.sendAndListen("(get-assignment)\n");
			if (response.contains(errorIndication)) {
				return smtConfig.responseFactory.error(response);
			}
			return smtConfig.responseFactory.unsupported(); // FIXME - need to return the assignment
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Z3 solver: " + e);
		}
	}

	@Override 
	public IResponse get_value(IExpr... terms) {
		IResponse status = super.get_value(terms);
		if (status.isError()) return status;
		try {
			solverProcess.sendNoListen("(get-value");
			for (IExpr e: terms) {
				solverProcess.sendNoListen(" ",translate(e));
			}
			String response = solverProcess.sendAndListen("\n");
			if (response.contains(errorIndication)) {
				return smtConfig.responseFactory.error(response);
			}
			return smtConfig.responseFactory.unsupported(); // FIXME - need to return the results
		} catch (IOException e) {
			return smtConfig.responseFactory.error("Error writing to Yices solver: " + e);
		} catch (IVisitor.VisitorException e) {
			return smtConfig.responseFactory.error("Error translating for Yices: " + e.getMessage());
		}
	}
	
	public /*@Nullable*/ String translate(IExpr expr) throws IVisitor.VisitorException {
		return expr.accept(new Translator());
	}
	
	public /*@Nullable*/ String translate(ISort expr) throws IVisitor.VisitorException {
		return expr.accept(new Translator());
	}
	
	/* Yices does not distinguish formulas and terms, so the mapping
	 * from SMT-LIB is simpler.
	 */
	
	static Map<String,String> fcnNames = new HashMap<String,String>();
	static Set<String> logicNames = new HashSet<String>();
	static {
		/* SMTLIB			YICES
		 * (or p q r ...)	(or p q r ...)
		 * (and p q r ...)	(and p q r ...)
		 * (not p)			(not p)
		 * (=> p q r ...)	(=> p (=> q r...))
		 * (xor p q r ...)	(/= (/= p q)) r )) ...
		 * (= p q r ...)	(and (= p q) (= q r) ... ) 
		 * (distinct p q r)	 conjunction of /= 
		 * true				true
		 * false			false
		 * (ite b p q)		(if b p q)
		 * (forall ...		(forall (a::Bool b::Int) expr)
		 * (exists ...		(exists (a::Bool b::Int) expr)
		 * (let ...			(let ((aux::int (f (f x)))) (g aux aux))
		 * 
		 * < <= > >=		< <= > >=  : no chaining allowed
		 *
		 * TERMS
		 * + - *			+ - * : left associative
		 * 	    			select store  - for arrays
		 * 
		 * 
		 * Yices has / mod div
		 */
		
	}
	

	/* Yices ids:
	 * 		FIXME - not  defined what Yices ids can be made of
	 */
	
	
	static public class Translator extends IVisitor.NullVisitor<String> {
		
		public Translator() {}

		@Override
		public String visit(IDecimal e) throws IVisitor.VisitorException {
			throw new VisitorException("The yices solver cannot handle decimal literals",e.pos());
		}

		@Override
		public String visit(IStringLiteral e) throws IVisitor.VisitorException {
			throw new VisitorException("The yices solver cannot handle string literals",e.pos());
		}

		@Override
		public String visit(INumeral e) throws IVisitor.VisitorException {
			return e.value().toString();
		}

		@Override
		public String visit(IBinaryLiteral e) throws IVisitor.VisitorException {
			throw new VisitorException("Did not expect a Binary literal in an expression to be translated",e.pos());
		}

		@Override
		public String visit(IHexLiteral e) throws IVisitor.VisitorException {
			throw new VisitorException("Did not expect a Hex literal in an expression to be translated",e.pos());
		}

		@Override
		public String visit(IFcnExpr e) throws IVisitor.VisitorException {
			Iterator<IExpr> iter = e.args().iterator();
			if (!iter.hasNext()) throw new VisitorException("Did not expect an empty argument list",e.pos());
			IQualifiedIdentifier fcn = e.head();
			String fcnname = fcn.accept(this);
			StringBuilder sb = new StringBuilder();
			int length  = e.args().size();
			if (fcnname.equals("or") || fcnname.equals("and")) {
				// operators that are still multi-arity
				sb.append("( ");
				sb.append(fcnname);
				while (iter.hasNext()) {
					sb.append(" ");
					sb.append(iter.next().accept(this));
				}
				sb.append(" )");
				return sb.toString();
			} else if (fcnname.equals("=") || fcnname.equals("<") || fcnname.equals(">") || fcnname.equals("<=") || fcnname.equals(">=")) {
				// chainable
				return remove_chainable(fcnname,iter);
			} else if (fcnname.equals("xor")) {
				fcnname = "/=";
				// left-associative operators that need grouping
				return remove_leftassoc(fcnname,length,iter);
			} else if (fcnname.equals("=>")) {
				// right-associative operators that need grouping
				if (!iter.hasNext()) {
					throw new VisitorException("=> operation without arguments",e.pos());
				}
				return remove_rightassoc(fcnname,iter);
			} else if (fcnname.equals("distinct")) {
				if (length == 2) {
					sb.append("(/=");
					while (iter.hasNext()) {
						sb.append(" ");
						sb.append(iter.next().accept(this));
					}
					sb.append(")");
				} else {
					int j = 0;
					sb.append("(and");
					while (iter.hasNext()) {
						IExpr n = iter.next();
						for (int k = 0; k<j; k++) {
							sb.append(" (/= ");
							sb.append(n.accept(this));
							sb.append(" ");
							sb.append(e.args().get(k).accept(this));
							sb.append(")");
						}
						++j;
					}
					sb.append(")");
				}
				return sb.toString();
			} else if (length == 1 && fcnname.equals("-")) {
				// In yices there is no negation: (- x) is just x
				// We express negation with (- 0 x)
				sb.append("(- 0 ");
				sb.append(iter.next().accept(this));
				sb.append(" )");
				return sb.toString();
			} else {
				// no associativity 
				sb.append("( ");
				sb.append(fcnname);
				while (iter.hasNext()) {
					sb.append(" ");
					sb.append(iter.next().accept(this));
				}
				sb.append(" )");
				return sb.toString();
			}
		}
			
		//@ requires iter.hasNext();
		private <T extends IExpr> String remove_rightassoc(String fcnname, Iterator<T> iter ) throws IVisitor.VisitorException {
			T n = iter.next();
			if (!iter.hasNext()) {
				return n.accept(this);
			} else {
				StringBuilder sb = new StringBuilder();
				sb.append("(");
				sb.append(fcnname);
				sb.append(" ");
				sb.append(n.accept(this));
				sb.append(" ");
				sb.append(remove_rightassoc(fcnname,iter));
				sb.append(")");
				return sb.toString();
			}
		}

		//@ requires iter.hasNext();
		//@ requires length > 0;
		private <T extends IExpr> String remove_leftassoc(String fcnname, int length, Iterator<T> iter ) throws IVisitor.VisitorException {
			if (length == 1) {
				return iter.next().accept(this);
			} else {
				StringBuilder sb = new StringBuilder();
				sb.append("(");
				sb.append(fcnname);
				sb.append(" ");
				sb.append(remove_leftassoc(fcnname,length-1,iter));
				sb.append(" ");
				sb.append(iter.next().accept(this));
				sb.append(")");
				return sb.toString();
			}
		}
		
		//@ requires iter.hasNext();
		//@ requires length > 0;
		private <T extends IAccept> String remove_chainable(String fcnname, Iterator<T> iter ) throws IVisitor.VisitorException {
			StringBuilder sb = new StringBuilder();
			sb.append("(and ");
			T left = iter.next();
			while (iter.hasNext()) {
				sb.append("(");
				sb.append(fcnname);
				sb.append(" ");
				sb.append(left.accept(this));
				sb.append(" ");
				sb.append((left=iter.next()).accept(this));
				sb.append(")");
			}
			sb.append(")");
			return sb.toString();
		}

		@Override
		public String visit(ISymbol e) throws IVisitor.VisitorException {
			return e.value(); // FIXME - translate
		}

		@Override
		public String visit(IKeyword e) throws IVisitor.VisitorException {
			throw new VisitorException("Did not expect a Keyword in an expression to be translated",e.pos());
		}

		@Override
		public String visit(IError e) throws IVisitor.VisitorException {
			throw new VisitorException("Did not expect a Error token in an expression to be translated", e.pos());
		}

		@Override
		public String visit(IParameterizedIdentifier e) throws IVisitor.VisitorException {
			throw new UnsupportedOperationException("visit-IParameterizedIdentifier");
		}

		@Override
		public String visit(IAsIdentifier e) throws IVisitor.VisitorException {
			throw new UnsupportedOperationException("visit-IAsIdentifier");
		}

		@Override
		public String visit(IForall e) throws IVisitor.VisitorException {
			StringBuffer sb = new StringBuffer();
			sb.append("(forall (");
			for (IDeclaration d: e.parameters()) {
				sb.append(d.parameter().accept(this));
				sb.append("::");
				sb.append(d.sort().accept(this));
				sb.append(" ");
			}
			sb.append(") ");
			sb.append(e.expr().accept(this));
			sb.append(")");
			return sb.toString();
		}

		@Override
		public String visit(IExists e) throws IVisitor.VisitorException {
			StringBuffer sb = new StringBuffer();
			sb.append("(exists (");
			for (IDeclaration d: e.parameters()) {
				sb.append(d.parameter().accept(this));
				sb.append("::");
				sb.append(d.sort().accept(this));
				sb.append(" ");
			}
			sb.append(") ");
			sb.append(e.expr().accept(this));
			sb.append(")");
			return sb.toString();
		}

		@Override
		public String visit(ILet e) throws IVisitor.VisitorException {
			StringBuffer sb = new StringBuffer();
			sb.append("(let (");
			for (IBinding d: e.bindings()) {
				sb.append("(");
				sb.append(d.parameter().accept(this));
				sb.append(" ");
				sb.append(d.expr().accept(this));
				sb.append(")");
			}
			sb.append(") ");
			sb.append(e.expr().accept(this));
			sb.append(")");
			return sb.toString();
		}

		@Override
		public String visit(IAttribute<?> e) throws IVisitor.VisitorException {
			throw new UnsupportedOperationException("visit-IAttribute");
		}

		@Override
		public String visit(IAttributedExpr e) throws IVisitor.VisitorException {
			return e.expr().accept(this); // FIXME - not doing anything with names
		}

		@Override
		public String visit(IDeclaration e) throws IVisitor.VisitorException {
			throw new UnsupportedOperationException("visit-IDeclaration");
		}

		public String visit(ISort.IFamily s) throws IVisitor.VisitorException {
			return s.identifier().accept(this);
		}
		
		public String visit(ISort.IAbbreviation s) throws IVisitor.VisitorException {
			throw new UnsupportedOperationException("visit-ISort.IAbbreviation");
		}
		
		public String visit(ISort.IExpression s) throws IVisitor.VisitorException {
			if (s.isBool()) return "bool";
			if (s.parameters().size() == 0) {
				String sort = s.family().accept(this);
				if ("Int".equals(sort)) return "int";
				if ("Real".equals(sort)) return "real";
				return sort;
			} else {
				throw new UnsupportedOperationException("visit-ISort.IExpression");
			}
		}
		public String visit(ISort.IFcnSort s) {
			throw new UnsupportedOperationException("visit-ISort.IFcnSort");
		}
		public String visit(ISort.IParameter s) {
			throw new UnsupportedOperationException("visit-ISort.IParameter");
		}
		
		
	}
}
