/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.platform.impl;

import org.python.core.Options;
import org.python.core.PyException;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxException;
import org.vertx.java.core.json.DecodeException;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Container;
import org.vertx.java.platform.PlatformManagerException;
import org.vertx.java.platform.Verticle;
import org.vertx.java.platform.VerticleFactory;

import java.io.*;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="https://github.com/sjhorn">Scott Horn</a>
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class JythonVerticleFactory implements VerticleFactory {

  private ClassLoader cl;
  private PythonInterpreter py;
  private static final AtomicInteger seq = new AtomicInteger();

  public static Vertx vertx;
  public static Container container;

  public JythonVerticleFactory() {
  }

  @Override
  public void init(Vertx vertx, Container container, ClassLoader cl) {
    this.cl = cl;
    JythonVerticleFactory.vertx = vertx;
    JythonVerticleFactory.container = container;
    System.setProperty("python.options.internalTablesImpl","weak");
    Thread.currentThread().setContextClassLoader(cl);
    Options.includeJavaStackInExceptions = false;
    this.py = new PythonInterpreter(null, new PySystemState());
  }

  public Verticle createVerticle(String main) throws Exception {
    return new JythonVerticle(main);
  }

  public void reportException(Logger logger, Throwable t) {
    if (t.getClass().getCanonicalName().contains("PyException")) {
      /*
      We have to adjust the line numbers in the stack trace so they are accurate
      Unfortunately Jython doesn't allow us to retrieve the stack trace as an array making this
      ugly
      */
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      BufferedReader rdr =  new BufferedReader(new StringReader(sw.toString()));
      String line;
      StringBuilder newStack = new StringBuilder();
      try {
        while ((line = rdr.readLine()) != null) {
          String newLine;
          if (line.contains(".py") && !line.contains("__pyclasspath__") && line.contains(", in ")) {
            String lineNumber = line.substring(0, line.lastIndexOf(", in "));
            int pos = lineNumber.lastIndexOf(", line ") + 7;
            lineNumber = lineNumber.substring(pos);
            int lineNo = Integer.parseInt(lineNumber);
            newLine = line.substring(0, pos) + (lineNo - 1) + line.substring(pos + lineNumber.length());
          } else {
            newLine = line;
          }
          newStack.append(newLine).append("\n");
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed to parse stack trace: " + e.getMessage());
      }
      logger.error("Exception in Python verticle: " + t.getMessage());
      logger.error(newStack);
    } else {
      logger.error("Exception in Python verticle", t);
    }
  }

  public void close() {
    py.cleanup();
  }

  private class JythonVerticle extends Verticle {

    private final String scriptName;
    private String funcName;
    private StringBuilder stopFuncName;
    private StringBuilder stopFuncVar;

    JythonVerticle(String scriptName) {
      this.scriptName = scriptName;
    }

    public void start() {
      try (InputStream is = cl.getResourceAsStream(scriptName)) {
        if (is == null) {
          throw new IllegalArgumentException("Cannot find verticle: " + scriptName);
        }
        // We wrap the python verticle in a function so different instances don't see each others top level vars
        String genName = "__VertxInternalVert__" + seq.incrementAndGet();
        funcName = "f" + genName;
        StringBuilder sWrap = new StringBuilder("def ").append(funcName).append("():\n");
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        for (String line = br.readLine(); line != null; line = br.readLine()) {
          // Append line indented by a tab
          sWrap.append("\t").append(line).append("\n");
        }
        br.close();
        // The return value of the wrapping function is the vertx_stop function (if defined)
        sWrap.append("\tif 'vertx_stop' in locals():\n");
        sWrap.append("\t\treturn vertx_stop\n");
        sWrap.append("\telse:\n");
        sWrap.append("\t\treturn None\n");

        // And then we have to add a top level wrapper method that calls the actual vertx_stop method
        stopFuncVar = new StringBuilder("v").append(genName);
        sWrap.append(stopFuncVar).append(" = ").append(funcName).append("()\n");
        stopFuncName = new StringBuilder(funcName).append("_stop");
        sWrap.append("def ").append(stopFuncName).append("():\n");
        sWrap.append("\tif ").append(stopFuncVar).append(" is not None:\n");
        sWrap.append("\t\t").append(stopFuncVar).append("()\n");



        // We have to convert it back to an inputstream since for some reason there is no version
        // py.exec which takes a String AND a fileName - and without the filename
        // any stack traces from errors won't show the filename and be hard for the user to parse.
        try (InputStream sis = new ByteArrayInputStream(sWrap.toString().getBytes("UTF-8"))) {
          py.execfile(sis, scriptName);
        }

      } catch (Exception e) {
        funcName = null;
        stopFuncName = null;
        stopFuncVar = null;
        throw new VertxException(e);
      }
    }

    public void stop() {
      if (stopFuncName != null) {
        py.exec(stopFuncName.toString() + "()");
        // And delete the globals
        py.exec("del " + stopFuncVar);
        py.exec("del " + stopFuncName);
        py.exec("del " + funcName);
      }
    }
  }


}