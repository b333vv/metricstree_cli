package org.b333vv.metric.library.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.SymbolResolver;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;

/**
 * A {@link JavaParserTypeSolver} whose re-parsed units can actually be resolved against.
 *
 * <h2>The problem</h2>
 * Since TASK-203 the analysis parses in a window and releases each unit once its class has been
 * analysed, so the project's own declarations are answered by re-parsing the file on demand instead
 * of by an in-memory index of every declaration. A re-parsed unit is a <em>second</em> AST of the
 * same source, and {@link JavaParserTypeSolver} does not attach a symbol resolver to it — only the
 * analysis' own units get one, in {@code JavaParserJavaMetricsAnalyzer.analyzeUnit}.
 *
 * <p>That is invisible until something resolves <em>through</em> a re-parsed unit. One visitor does:
 * {@code JavaParserDepthOfInheritanceTreeMetricVisitor} walks up the {@code extends} chain, and from
 * the second link onwards it is reading a declaration whose wrapped node belongs to a re-parsed unit.
 * Calling {@code resolve()} there fails with {@code IllegalStateException: No data of this type
 * found}, so DIT understated by one per link and reported a resolution failure for a chain that is
 * perfectly resolvable.
 *
 * <h2>The fix</h2>
 * Hand the unit the same resolver the analysis' own units carry. The resolver is the one attached to
 * the root solver, so a re-parsed unit resolves exactly as the unit being analysed does — the two are
 * the same source, and the answers are the same.
 *
 * <p>This sits in a decorator rather than in the visitor because it is solver plumbing, not metric
 * logic: a visitor should not have to know which AST it is holding. It also means the next visitor
 * that reads a resolved declaration's AST gets a working one without having to know this happened.
 *
 * <p>Attachment is guarded by the unit itself. Several workers can reach the same re-parsed unit
 * concurrently, and {@code Node.setData} is not thread-safe; the guard is per unit and the work
 * inside it happens once, so the contention is negligible.
 */
final class ResolverAttachingTypeSolver implements TypeSolver {

    private final JavaParserTypeSolver delegate;
    private final SymbolResolver symbolResolver;

    /**
     * @param delegate       the solver that finds and re-parses the files
     * @param symbolResolver the resolver the analysis attaches to its own units
     */
    ResolverAttachingTypeSolver(JavaParserTypeSolver delegate, SymbolResolver symbolResolver) {
        this.delegate = delegate;
        this.symbolResolver = symbolResolver;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        SymbolReference<ResolvedReferenceTypeDeclaration> reference = delegate.tryToSolveType(name);
        if (reference.isSolved()) {
            attachResolver(reference.getCorrespondingDeclaration());
        }
        return reference;
    }

    private void attachResolver(ResolvedReferenceTypeDeclaration declaration) {
        ClassOrInterfaceDeclaration wrappedNode = wrappedNodeOf(declaration);
        if (wrappedNode == null) {
            // A declaration that did not come from source — the JDK, a jar, a class directory. It has
            // no AST to attach anything to.
            return;
        }
        wrappedNode.findCompilationUnit().ifPresent(unit -> attachResolver(unit));
    }

    private void attachResolver(CompilationUnit unit) {
        // The check is repeated outside the lock on purpose: attachment happens once per unit and
        // resolution happens constantly, so the steady state must not take the monitor at all. This
        // used to lock on every call, which put a shared monitor on the hottest path in the analysis
        // for no benefit — the data is either there or it is not.
        if (unit.containsData(Node.SYMBOL_RESOLVER_KEY)) {
            return;
        }
        synchronized (unit) {
            if (!unit.containsData(Node.SYMBOL_RESOLVER_KEY)) {
                unit.setData(Node.SYMBOL_RESOLVER_KEY, symbolResolver);
            }
        }
    }

    private static ClassOrInterfaceDeclaration wrappedNodeOf(ResolvedReferenceTypeDeclaration declaration) {
        if (declaration instanceof JavaParserClassDeclaration classDeclaration) {
            return classDeclaration.getWrappedNode();
        }
        if (declaration instanceof JavaParserInterfaceDeclaration interfaceDeclaration) {
            return interfaceDeclaration.getWrappedNode();
        }
        return null;
    }

    @Override
    public TypeSolver getParent() {
        return delegate.getParent();
    }

    @Override
    public void setParent(TypeSolver parent) {
        // Forwarded, because the delegate is the solver that has to reach the root: it is the one
        // that builds declarations and looks up its own parent chain.
        delegate.setParent(parent);
    }
}
