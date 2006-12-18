/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.gentools;

import java.io.PrintStream;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * @author kpvdr
 * Class to represent the &lt;constant&gt; declaration within the AMQP specification.
 * Currently, only integer values exist within the specification, however looking forward
 * to other possible types in the future, string and double types are also supported.
 * 
 * The &lt;constant&gt; declaration in the specification contains only two attributes:
 * name and value.
 * 
 * The value of the constant is mapped against the version(s) for which the name is defined.
 * This allows for a change in the value rather than the name only from one version to the next.
 */
@SuppressWarnings("serial")
public class AmqpConstant extends TreeMap<String, AmqpVersionSet>
    implements Printable, VersionConsistencyCheck, Comparable<AmqpConstant>
{
    /**
     * Constant name as defined by the name attribute of the &lt;constant&gt; declaration.
     */
    protected String name;
    
    /**
     * Set of versions for which this constant name is defined.
     */
    protected AmqpVersionSet versionSet;

    /**
     * Constructor
     * @param name Constant name as defined by the name attribute of the &lt;constant&gt; declaration.
     * @param value Constant value as defined by the value attribute of the &lt;constant&gt; declaration.
     * @param version AMQP version for which this constant is defined
     */
    public AmqpConstant (String name, String value, AmqpVersion version)
    {
        this.name = name;
        versionSet = new AmqpVersionSet(version);
        AmqpVersionSet valueVersionSet = new  AmqpVersionSet(version);
        put(value, valueVersionSet);
     }

    /**
     * Constructor
     * @param name Constant name as defined by the name attribute of the &lt;constant&gt; declaration.
     * @param value Constant value as defined by the value attribute of the &lt;constant&gt; declaration.
     * @param version AMQP version for which this constant is defined
     */
    public AmqpConstant (String name, int value, AmqpVersion version)
    {
        this.name = name;
        versionSet = new AmqpVersionSet(version);
        AmqpVersionSet valueVersionSet = new  AmqpVersionSet(version);
        put(String.valueOf(value), valueVersionSet);
    }

    /**
     * Constructor
     * @param name Constant name as defined by the name attribute of the &lt;constant&gt; declaration.
     * @param value Constant value as defined by the value attribute of the &lt;constant&gt; declaration.
     * @param version AMQP version for which this constant is defined
     */
    public AmqpConstant (String name, double value, AmqpVersion version)
    {
        this.name = name;
        versionSet = new AmqpVersionSet(version);
        AmqpVersionSet valueVersionSet = new  AmqpVersionSet(version);
        put(String.valueOf(value), valueVersionSet);
    }

    /**
     * Get the name of this constant.
     * @return Name of this constant, being the name attribute of the &lt;constant&gt; declaration
     * represented by this class.
     */
    public String getName()
    {
        return name;
    }
    
    /**
     * Get the value of this constant as a String.
     * @param version AMQP version for which this value is required.
     * @return Value of this constant, being the value attribute of the &lt;constant&gt; declaration
     * represented by this class.
     * @throws AmqpTypeMappingException when a value is requested for a version for which it is not
     * defined in the AMQP specifications.
     */
    public String getStringValue(AmqpVersion version)
    throws AmqpTypeMappingException
    {
        Iterator<String> sItr = keySet().iterator();
        while (sItr.hasNext())
        {
            String value = sItr.next();
            AmqpVersionSet versionSet = get(value);
            if (versionSet.contains(version))
                return value;
        }
        throw new AmqpTypeMappingException("Unable to find value for constant \"" + name +
            "\" for version " + version.toString() + ".");
    }
    
    /**
     * Get the value of this constant as an integer.
     * @param version AMQP version for which this value is required.
     * @return Value of this constant, being the value attribute of the &lt;constant&gt; declaration
     * represented by this class.
     * @throws AmqpTypeMappingException when a value is requested for a version for which it is not
     * defined in the AMQP specifications.
     */
    public int getIntegerValue(AmqpVersion version)
    throws AmqpTypeMappingException
    {
        return Integer.parseInt(getStringValue(version));
    }
    
    /**
     * Get the value of this constant as a double.
     * @param version AMQP version for which this value is required.
     * @return Value of this constant, being the value attribute of the &lt;constant&gt; declaration
     * represented by this class.
     * @throws AmqpTypeMappingException when a value is requested for a version for which it is not
     * defined in the AMQP specifications.
     */
    public double getDoubleValue(AmqpVersion version)
    throws AmqpTypeMappingException
    {
        return Double.parseDouble(getStringValue(version));
    }
    
    /**
     * Get the version set for this constant. It contains the all the versions for which this
     * constant name exists.
     * @return Set of versions for which this constant exists.
     */
    public AmqpVersionSet getVersionSet()
    {
        return versionSet;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    
    public int compareTo(AmqpConstant other)
    {
        int res = name.compareTo(other.name);
        if (res != 0)
            return res;
        return versionSet.compareTo(other.versionSet);
    }

    /* (non-Javadoc)
     * @see org.apache.qpid.gentools.VersionConsistencyCheck#isVersionConsistent(org.apache.qpid.gentools.AmqpVersionSet)
     */
    public boolean isVersionConsistent(AmqpVersionSet globalVersionSet)
    {
        if (size() != 1)
            return false;
        return get(firstKey()).equals(globalVersionSet);
    }

    /* (non-Javadoc)
     * @see org.apache.qpid.gentools.Printable#print(java.io.PrintStream, int, int)
     */
    public void print(PrintStream out, int marginSize, int tabSize)
    {
        String margin = Utils.createSpaces(marginSize);
        String tab = Utils.createSpaces(tabSize);
        if (size() == 1)
        {
            out.println(margin + tab + "[C] " + name + " = \"" + firstKey() + "\" " + versionSet);
        }
        else
        {
            out.println(margin + tab + "[C] " + name + ": " + versionSet);
            Iterator<String> sItr = keySet().iterator();
            while (sItr.hasNext())
            {
                String value = sItr.next();
                out.println(margin + tab + tab + "= \"" + value + "\" " + get(value));
            }
        }
    }
}
