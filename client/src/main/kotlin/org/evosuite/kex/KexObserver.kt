package org.evosuite.kex

import org.evosuite.testcase.execution.ExecutionObserver
import org.evosuite.testcase.execution.ExecutionResult
import org.evosuite.testcase.execution.Scope
import org.evosuite.testcase.statements.*
import org.evosuite.testcase.variable.*
import org.evosuite.utils.generic.GenericField
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.ktype.kexType
import org.vorpal.research.kex.parameters.Parameters
import org.vorpal.research.kex.state.predicate.Predicate
import org.vorpal.research.kex.state.predicate.state
import org.vorpal.research.kex.state.term.Term
import org.vorpal.research.kex.state.term.term
import org.vorpal.research.kex.trace.symbolic.InstructionTraceCollector
import org.vorpal.research.kex.trace.symbolic.StateClause
import org.vorpal.research.kex.trace.symbolic.SymbolicTraceBuilder
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.disableCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.enableCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.initializeEmptyCollector
import org.vorpal.research.kex.trace.symbolic.TraceCollectorProxy.setCurrentCollector
import org.vorpal.research.kex.util.toKfgType
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.ir.Field
import org.vorpal.research.kfg.ir.MethodDescriptor
import org.vorpal.research.kfg.ir.value.*
import org.vorpal.research.kfg.ir.value.instruction.BinaryOpcode
import org.vorpal.research.kfg.ir.value.instruction.CmpOpcode
import org.vorpal.research.kfg.ir.value.instruction.Instruction
import org.vorpal.research.kfg.ir.value.instruction.InstructionBuilder
import org.vorpal.research.kthelper.assert.unreachable
import ru.spbstu.wheels.runIf
import java.lang.reflect.Method


class KexObserver(private val executionContext: ExecutionContext) : ExecutionObserver(), InstructionBuilder {
    override val cm: ClassManager
        get() = executionContext.cm
    override val ctx: UsageContext = EmptyUsageContext

    private var collector: SymbolicTraceBuilder
    private val emptyCollector: InstructionTraceCollector

    private val stateBuilder get() = collector.stateBuilder

    private val valueCache = mutableMapOf<VariableReference, Value>()
    private val termCache = mutableMapOf<Value, Term>()

    init {
        collector = enableCollector(executionContext, NameMapperContext()) as SymbolicTraceBuilder
        emptyCollector = initializeEmptyCollector()
    }

    override fun output(position: Int, output: String) {
        // Nothing
    }

    override fun beforeStatement(statement: Statement, scope: Scope) {
        when (statement) {
            is FieldStatement -> handleField(statement, scope)
            is ArrayStatement -> handleArray(statement, scope)
            is PrimitiveExpression -> handleExpression(statement, scope)
            is AssignmentStatement -> handleAssignment(statement, scope)
            is PrimitiveStatement<*> -> handlePrimitive(statement, scope)
            is ConstructorStatement -> handleConstructor(statement, scope)
            is MethodStatement -> handleMethod(statement, scope)
            is FunctionalMockStatement -> handleMock(statement, scope)
        }

        setCurrentCollector(collector)
    }

    private fun handleField(statement: FieldStatement, scope: Scope) {
        val field = statement.field.kfgField
        val owner = statement.source?.let { mkValue(it) }

        val name = statement.returnValue.name
        val instruction = if (field.isStatic) {
            field.load(name)
        } else {
            owner!!.load(name, field)
        }

        val valueTerm = register(statement.returnValue, instruction)
        val ownerTerm = owner?.let { mkTerm(it) }

        val predicate = state {
            val actualOwner = ownerTerm ?: staticRef(field.klass)
            valueTerm equality actualOwner.field(field.type.kexType, field.name).load()
        }

        postProcess(instruction, predicate)
    }

    private fun handleArray(statement: ArrayStatement, scope: Scope) {
        TODO()
    }

    private fun handleConstructor(statement: ConstructorStatement, scope: Scope) {
        TODO()
    }

    private fun handleMethod(statement: MethodStatement, scope: Scope) {
        val method = statement.method.method.kfgMethod
        val callee = statement.callee?.let { mkValue(it) }
        val args = statement.parameterReferences.map { mkValue(it) }

        val name = statement.returnValue.name
        val isVoid = statement.returnType == Void.TYPE
        val instruction = when {
            method.isStatic && isVoid -> method.staticCall(method.klass, args)
            method.isStatic -> method.staticCall(method.klass, name, args)

            method.isConstructor && isVoid -> method.specialCall(method.klass, callee!!, args)
            method.isConstructor -> method.specialCall(method.klass, name, callee!!, args)

            method.klass.isInterface && isVoid -> method.interfaceCall(method.klass, callee!!, args)
            method.klass.isInterface -> method.interfaceCall(method.klass, name, callee!!, args)

            isVoid -> method.virtualCall(method.klass, callee!!, args)
            else -> method.virtualCall(method.klass, name, callee!!, args)
        }

        val valueTerm = runIf(!isVoid) { register(statement.returnValue, instruction) }
        val calleeTerm = callee?.let { mkTerm(it) }
        val argsTerm = args.map { mkTerm(it) }

        val predicate = state {
            val actualCallee = calleeTerm ?: staticRef(method.klass)
            val callTerm = actualCallee.call(method, argsTerm)
            valueTerm?.call(callTerm) ?: call(callTerm)
        }

        collector.lastCall = SymbolicTraceBuilder.Call(
            instruction, method,
            valueTerm?.let { instruction to it },
            Parameters(calleeTerm, argsTerm), predicate
        )
    }

    private fun handleMock(statement: FunctionalMockStatement, scope: Scope) {
        TODO("Not supported in Kex")
    }

    private fun handleExpression(statement: PrimitiveExpression, scope: Scope) {
        val lhv = mkValue(statement.leftOperand)
        val rhv = mkValue(statement.rightOperand)

        val binOpcode = statement.operator.getBinOpcode()
        val cmpOpcode = statement.operator.getCmpOpcode()

        val name = statement.returnValue.name
        val instruction = if (binOpcode != null) {
            binary(name, binOpcode, lhv, rhv)
        } else if (cmpOpcode != null) {
            cmp(name, statement.returnClass.toKfgType(types), cmpOpcode, lhv, rhv)
        } else {
            unreachable { }
        }

        val valueTerm = register(statement.returnValue, instruction)
        val lhvTerm = mkTerm(lhv)
        val rhvTerm = mkTerm(rhv)

        val predicate = state {
            if (binOpcode != null) {
                valueTerm equality lhvTerm.apply(types, binOpcode, rhvTerm)
            } else if (cmpOpcode != null) {
                valueTerm equality lhvTerm.apply(cmpOpcode, rhvTerm)
            } else {
                unreachable { }
            }
        }

        stateBuilder += StateClause(instruction, predicate)
    }

    private fun PrimitiveExpression.Operator.getCmpOpcode(): CmpOpcode? = when (this) {
        PrimitiveExpression.Operator.LESS -> CmpOpcode.LT
        PrimitiveExpression.Operator.GREATER -> CmpOpcode.GT
        PrimitiveExpression.Operator.LESS_EQUALS -> CmpOpcode.LE
        PrimitiveExpression.Operator.GREATER_EQUALS -> CmpOpcode.GE
        PrimitiveExpression.Operator.EQUALS -> CmpOpcode.EQ
        PrimitiveExpression.Operator.NOT_EQUALS -> CmpOpcode.NEQ
        else -> null
    }

    private fun PrimitiveExpression.Operator.getBinOpcode(): BinaryOpcode? = when (this) {
        PrimitiveExpression.Operator.TIMES -> BinaryOpcode.MUL
        PrimitiveExpression.Operator.DIVIDE -> BinaryOpcode.DIV
        PrimitiveExpression.Operator.REMAINDER -> BinaryOpcode.REM
        PrimitiveExpression.Operator.PLUS -> BinaryOpcode.ADD
        PrimitiveExpression.Operator.MINUS -> BinaryOpcode.SUB
        PrimitiveExpression.Operator.LEFT_SHIFT -> BinaryOpcode.SHL
        PrimitiveExpression.Operator.RIGHT_SHIFT_SIGNED -> BinaryOpcode.SHR
        PrimitiveExpression.Operator.RIGHT_SHIFT_UNSIGNED -> BinaryOpcode.USHR
        PrimitiveExpression.Operator.XOR -> BinaryOpcode.XOR
        PrimitiveExpression.Operator.AND, PrimitiveExpression.Operator.CONDITIONAL_AND -> BinaryOpcode.AND
        PrimitiveExpression.Operator.OR, PrimitiveExpression.Operator.CONDITIONAL_OR -> BinaryOpcode.OR
        else -> null
    }

    private fun handleAssignment(statement: AssignmentStatement, scope: Scope) {
        val value = mkValue(statement.value)
        val termValue = mkTerm(value)

        val clause = when (val retval = statement.returnValue) {
            is ArrayIndex -> TODO()
            is FieldReference -> {
                val field = retval.field.kfgField
                val owner = retval.source?.let { mkValue(it) }

                val instruction = if (retval.field.isStatic) {
                    field.store(value)
                } else {
                    owner!!.store(field, value)
                }

                val termOwner = owner?.let { mkTerm(it) }

                val predicate = state {
                    val actualOwner = termOwner ?: staticRef(field.klass)
                    actualOwner.field(field.type.kexType, field.name).store(termValue)
                }

                StateClause(instruction, predicate)
            }

            else -> unreachable { }
        }

        postProcess(clause)
    }

    private fun handlePrimitive(statement: PrimitiveStatement<*>, scope: Scope) {
        // TODO: handle non-primitives
        val value = buildValue(statement.value, statement.returnClass)
        register(statement.returnValue, value)
    }

    private fun postProcess(instruction: Instruction, predicate: Predicate) {
        postProcess(StateClause(instruction, predicate))
    }

    private fun postProcess(clause: StateClause) {
        stateBuilder += clause
    }

    private fun register(ref: VariableReference, value: Value): Term {
        valueCache[ref] = value
        return mkNewTerm(value)
    }

    private fun mkValue(ref: VariableReference): Value =
        valueCache.getOrElse(ref) { mkNewValue(ref) }

    private fun mkNewValue(ref: VariableReference): Value = when (ref) {
        is NullReference -> values.nullConstant
        is ConstantValue -> buildValue(ref.value, ref.genericClass.rawClass)
        is ArrayReference -> TODO()
        is ArrayIndex -> TODO()
        is FieldReference -> TODO()
        else -> TODO()
    }.also { valueCache[ref] = it }

    private fun buildValue(value: Any?, clazz: Class<*>): Value = when (clazz) {
        Boolean::class.java -> values.getBool(value as Boolean)
        Byte::class.java -> values.getByte(value as Byte)
        Char::class.java -> values.getChar(value as Char)
        Short::class.java -> values.getShort(value as Short)
        Int::class.java -> values.getInt(value as Int)
        Long::class.java -> values.getLong(value as Long)
        Float::class.java -> values.getFloat(value as Float)
        Double::class.java -> values.getDouble(value as Double)
        String::class.java -> values.getString(value as String)
        Class::class.java -> values.getClass(types.get(value as Class<*>))
        Method::class.java -> values.getMethod((value as Method).kfgMethod)
        else -> unreachable { }
    }

    private val Method.kfgMethod
        get() = cm[declaringClass.name].getMethod(
            name, MethodDescriptor(
                parameterTypes.map(types::get),
                if (returnType == Void.TYPE) { types.voidType } else { types.get(returnType) }
            )
        )

    private val GenericField.kfgField: Field
        get() {
            val cl = cm[ownerClass.className]
            val type = types.get(rawGeneratedType)
            return cl.getField(name, type)
        }

    private fun mkTerm(value: Value): Term = termCache.getOrElse(value) { mkNewTerm(value) }

    private fun mkNewTerm(value: Value): Term = term {
        if (value is Constant) {
            const(value) // TODO: make it symbolic here?
        } else {
            termFactory.getValue(value.type.kexType, collector.nameGenerator.nextName(value.name.toString()))
        }
    }.also { termCache[value] = it }

    override fun afterStatement(statement: Statement, scope: Scope, exception: Throwable?) {
        setCurrentCollector(emptyCollector)

        collector.lastCall?.let {
            postProcess(it.call, it.predicate)
        }
        collector.lastCall = null
    }

    override fun testExecutionFinished(r: ExecutionResult, s: Scope) {
        disableCollector()
    }

    override fun clear() {
        collector = SymbolicTraceBuilder(executionContext, NameMapperContext())
        valueCache.clear()
        termCache.clear()
        setCurrentCollector(emptyCollector)
    }
}
