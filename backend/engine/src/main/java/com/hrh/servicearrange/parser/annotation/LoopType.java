package com.hrh.servicearrange.parser.annotation;

import java.lang.annotation.*;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoopType {

    /**
     * 循环模式 do{}while()
     */
    public static final String DO_WHILE = "doWhile";
    /**
     * 循环模式 while()do{}
     */
    public static final String WHILE_DO = "whileDo";
}
