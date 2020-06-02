package Utils

import com.google.inject.BindingAnnotation
//binding with annotations Tutorial on Guice
@Target(
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPE, AnnotationTarget.FUNCTION
)
@Retention(AnnotationRetention.RUNTIME)
@BindingAnnotation
annotation class torrentStorage


@Target(
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPE, AnnotationTarget.FUNCTION
)
@Retention(AnnotationRetention.RUNTIME)
@BindingAnnotation
annotation class statsStorage

@Target(
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPE, AnnotationTarget.FUNCTION
)
@Retention(AnnotationRetention.RUNTIME)
@BindingAnnotation
annotation class peerStorage