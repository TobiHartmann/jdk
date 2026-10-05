/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8392533 8393322
 * @summary Test null checks for field accesses with empty value classes.
 * @enablePreview
 * @library /test/lib
 * @modules java.base/jdk.internal.vm.annotation
 * @run main ${test.main.class}
 * @run main/othervm -Xbatch -XX:TieredStopAtLevel=1 ${test.main.class}
 */

package compiler.valhalla.valuetypes;

import jdk.internal.vm.annotation.NullRestricted;
import jdk.test.lib.Asserts;

public class TestEmptyValueFieldNullChecks {
    static value class Empty { }

    static value class Holder {
        @NullRestricted
        Empty empty;

        Holder() {
            empty = new Empty();
            super();
        }
    }

    Empty empty;

    @NullRestricted
    Holder holder;

    TestEmptyValueFieldNullChecks() {
        empty = new Empty();
        holder = new Holder();
        super();
    }

// TODO null free empty store? More versions?
// https://mach5.us.oracle.com/mdash/jobs/tobias.hartmann-jdk-20261002-0727-51208817?search=result.status%3A*

    // An empty store emits no payload store but must null-check its holder
    static void testNullableEmptyPutfield(TestEmptyValueFieldNullChecks holder) {
        holder.empty = null;
    }

    // A recursively empty store emits no payload access but must null-check both operands
    static void testPutfield(TestEmptyValueFieldNullChecks holder, Holder value) {
        holder.holder = value;
    }

    // A recursively empty load emits no payload access but must null-check its holder
    static Holder testGetfield(TestEmptyValueFieldNullChecks holder) {
        return holder.holder;
    }

    public static void main(String[] args) {
        TestEmptyValueFieldNullChecks outerHolder = new TestEmptyValueFieldNullChecks();
        Holder innerHolder = new Holder();
        for (int i = 0; i < 20_000; i++) {
            testNullableEmptyPutfield(outerHolder);
            testPutfield(outerHolder, innerHolder);
            Asserts.assertEquals(testGetfield(outerHolder), outerHolder.holder);
        }

        Asserts.assertThrows(NullPointerException.class, () -> testNullableEmptyPutfield(null));
        Asserts.assertThrows(NullPointerException.class, () -> testPutfield(null, innerHolder));
        Asserts.assertThrows(NullPointerException.class, () -> testPutfield(outerHolder, null));
        Asserts.assertThrows(NullPointerException.class, () -> testGetfield(null));
    }
}

