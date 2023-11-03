// MEMBER_NAME_FILTER: property
package properties

@Target(
    AnnotationTarget.TYPE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.PROPERTY_GETTER,
)
annotation class Anno(val position: String)
const val constant = 0

class My<caret>Class(
    @property:Anno("property $constant")
    @get:Anno("get $constant")
    @set:Anno("set $constant")
    @setparam:Anno("set $constant")
    @field:Anno("field $constant")
    @param:Anno("param $constant")
    var property: @Anno("parameter type: $constant") List<@Anno("nested parameter type: $constant") List<@Anno("nested nested parameter type: $constant") Int>>,
)
