package com.lightningkite.lightningserver.demo

import java.io.*
import java.util.*


object ClasspathIterator {
    @JvmStatic
    fun main(args: Array<String>) {
        val classpath = System.getProperty("java.class.path")
        val classpathEntries =
            classpath!!.split(File.pathSeparator.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        println("Classpath entries:")
        for (entry in classpathEntries) {
            val file = File(entry)
            print("- " + file.getAbsolutePath())

            if (file.exists()) {
                if (file.isDirectory()) {
                    println(" (Directory)")
                    // Optional: list contents of the directory
                    // for (File f : file.listFiles()) {
                    //     System.out.println("    - " + f.getName());
                    // }
                } else if (file.isFile() && entry.lowercase(Locale.getDefault()).endsWith(".jar")) {
                    println(" (JAR File)")
                    // Optional: process JAR file contents
                } else {
                    println(" (File)")
                }
            } else {
                println(" (Does not exist)")
            }
        }
    }
}