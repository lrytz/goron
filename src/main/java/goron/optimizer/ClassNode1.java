/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer;

import scala.tools.asm.MethodVisitor;
import scala.tools.asm.Opcodes;
import scala.tools.asm.tree.ClassNode;
import scala.tools.asm.tree.MethodNode;

public class ClassNode1 extends ClassNode {
    public ClassNode1() {
        this(Opcodes.ASM9);
    }

    public ClassNode1(int api) {
        super(api);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodNode method = new MethodNode1(access, name, descriptor, signature, exceptions);
        methods.add(method);
        return method;
    }
}
