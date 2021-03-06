package grails.gorm.hibernate.mapping

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.config.MappingDefinition
import org.grails.orm.hibernate.cfg.Mapping
import org.grails.orm.hibernate.cfg.PropertyConfig

/**
 * Entry point for the ORM mapping configuration DSL
 *
 * @author Graeme Rocher
 * @since 6.1
 */
@CompileStatic
class MappingBuilder {

    /**
     * Build a Hibernate mapping
     *
     * @param mappingDefinition The closure defining the mapping
     * @return The mapping
     */
    static MappingDefinition<Mapping, PropertyConfig> define(@DelegatesTo(Mapping) Closure mappingDefinition) {
        new ClosureMappingDefinition(mappingDefinition)
    }

    @CompileStatic
    private static class ClosureMappingDefinition implements MappingDefinition<Mapping, PropertyConfig> {
        final Closure definition
        private Mapping mapping

        ClosureMappingDefinition(Closure definition) {
            this.definition = definition
        }

        @Override
        Mapping configure(Mapping existing) {
            if(mapping == null) {
                mapping = Mapping.configureExisting(existing, definition)
            }
            return mapping
        }

        @Override
        Mapping build() {
            if(mapping == null) {
                mapping = Mapping.configureNew(definition)
            }
            return mapping
        }

        @Override
        Map<String, PropertyConfig> getProperties() {
            if(mapping == null) {
                throw new IllegalStateException("Call configure(..) or build() first")
            }
            return mapping.columns
        }
    }
}
