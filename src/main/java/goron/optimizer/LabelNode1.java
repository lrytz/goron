/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer;

import scala.tools.asm.Label;
import scala.tools.asm.tree.LabelNode;

public class LabelNode1 extends LabelNode {
    public LabelNode1() {
    }

    public LabelNode1(Label label) {
        super(label);
    }

    public int flags;
}
