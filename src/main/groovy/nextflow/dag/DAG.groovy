/*
 * Copyright (c) 2013-2016, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2016, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.dag
import groovy.transform.PackageScope
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowChannel
import groovyx.gpars.dataflow.expression.DataflowExpression
import nextflow.Session
import nextflow.script.DefaultInParam
import nextflow.script.DefaultOutParam
import nextflow.script.InParam
import nextflow.script.InputsList
import nextflow.script.OutParam
import nextflow.script.OutputsList
import nextflow.script.SetInParam
import nextflow.script.SetOutParam
/**
 * Model a direct acyclic graph of the pipeline execution.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class DAG {

    @PackageScope
    static enum Type {
        PROCESS,
        OPERATOR,
        ORIGIN,
        NODE
    }

    /**
     * The list of edges in the graph
     */
    private List<Edge> edges = new ArrayList<>(50)

    /**
     * The ordered list of vertices
     */
    private List<Vertex> vertices = new ArrayList<>(50)

    /**
     * The {@link Session} to which this DAG is bound
     */
    private Session session

    @PackageScope
    List<Vertex> getVertices() { vertices }

    @PackageScope
    List<Edge> getEdges() { edges }

    boolean isEmpty() { edges.size()==0 && vertices.size()==0 }

    /**
     *  Creates a new vertex in the DAG representing a computing `process`
     *
     * @param label The label associated to the process
     * @param inputs The list of inputs entering in the process
     * @param outputs the list of outputs leaving the process
     */
    void addProcessNode( String label, InputsList inputs, OutputsList outputs ) {
        assert label
        assert inputs
        assert outputs
        addVertex( Type.PROCESS, label, normalizeInputs(inputs), normalizeOutputs(outputs))
    }

    /**
     * Creates a new DAG vertex representing a dataflow operator
     *
     * @param label The operator label
     * @param inputs The operator input(s). It can be either a single channel or a list of channels.
     * @param outputs The operator output(s). It can be either a single channel, a list of channels or {@code null} if the operator has no output.
     */
    public void addOperatorNode( String label, inputs, outputs )  {
        assert label
        assert inputs
        addVertex(Type.OPERATOR, label, normalizeChannels(inputs), normalizeChannels(outputs) )
    }

    /**
     * Creates a vertex in the DAG representing a dataflow channel source.
     *
     * @param label The node description
     * @param source Either a dataflow channel or a list of channel.
     */
    void addSourceNode( String label, source )  {
        assert label
        assert source
        addVertex(Type.ORIGIN, label, null, normalizeChannels(source) )
    }

    /**
     * Creates a vertex and adds it to the DAG
     *
     * @param type A {link Type} value
     * @param label The vertex description
     * @param inbounds The inbounds channels to this vertex
     * @param outbounds The outbounds channels leaving the vertex
     */
    @PackageScope
    void addVertex( Type type, String label, List<ChannelHandler> inbounds, List<ChannelHandler> outbounds ) {

        def vertex = createVertex( type, label)

        inbounds?.each { ChannelHandler channel ->
            inbound( vertex, channel )
        }

        outbounds?.each { ChannelHandler channel ->
            outbound( vertex, channel )
        }
    }

    /**
     * Creates a DAG vertex object
     *
     * @param type The vertex type
     * @param label The vertex label
     * @return A {@link Vertex} object
     */
    @PackageScope
    Vertex createVertex( Type type, String label ) {
        def result = new Vertex(type, label)
        vertices << result
        return result
    }

    private void inbound( Vertex vertex, ChannelHandler entering )  {

        // look for an existing edge for the given dataflow channel
        def edge = findEdge(entering.channel)

        // if does not exist just create it
        if( !edge ) {
            edges << new Edge(channel: entering.channel, to: vertex, label: entering.label)
        }
        // link the edge to given `edge`
        else if( edge.to == null ) {
            edge.to = vertex
        }
        // handle the special case for dataflow variable
        // this kind of channel can be used more than one time as an input
        else if( entering.channel instanceof DataflowExpression ) {
            if( !edge.from ) {
                edge.from = new Vertex(Type.ORIGIN);
                int p = vertices.indexOf(edge.to)
                if(p!=-1) vertices.add(p,edge.from)
                else vertices.add(edge.from)
            }
            def fork = new Edge(channel: entering.channel, from: edge.from, to: vertex, label: entering.label)
            edges << fork
        }
        // the same channel - apart the above case - cannot be used multiple times as an input
        // thus throws an exception
        else {
            final name = getChannelName(entering)
            throw new MultipleInputChannelException(name, entering, vertex, edge.to)
        }
    }

    private void outbound( Vertex vertex, ChannelHandler leaving) {

        // look for an existing edge for the given dataflow channel
        final edge = findEdge(leaving.channel)
        if( !edge ) {
            edges << new Edge(channel: leaving.channel, from: vertex, label: leaving.label)
        }
        else if( edge.from == null ) {
            edge.from = vertex
        }
        // the same channel cannot be used multiple times as an output
        // thus throws an exception
        else {
            final name = getChannelName(leaving)
            throw new MultipleOutputChannelException(name, leaving, vertex, edge.to)
        }

    }

    static private List<ChannelHandler> normalizeInputs( InputsList inputs ) {

        inputs
                .findAll { !( it instanceof DefaultInParam)  }
                .collect { InParam p -> new ChannelHandler(channel: (DataflowChannel)p.inChannel, label: p instanceof SetInParam ? null : p.name) }

    }

    static private List<ChannelHandler> normalizeOutputs( OutputsList outputs ) {

        def result = []
        outputs.each { OutParam p ->
            if( p instanceof DefaultOutParam ) return
            p.outChannels.each {
                result << new ChannelHandler(channel: (DataflowChannel)it, label: p instanceof SetOutParam ? null : p.name)
            }
        }

        return result
    }

    static private List<ChannelHandler> normalizeChannels( entry ) {
        if( entry == null ) {
            Collections.emptyList()
        }
        else if( entry instanceof DataflowChannel ) {
            [ new ChannelHandler(channel: entry) ]
        }
        else if( entry instanceof Collection || entry instanceof Object[] ) {
            entry.collect { new ChannelHandler(channel: (DataflowChannel)it) }
        }
        else {
            throw new IllegalArgumentException("Not a valid channel type: [${entry.class.name}]")
        }
    }

    @PackageScope
    Edge findEdge( DataflowChannel channel ) {
        edges.find { edge -> edge.channel.is(channel) }
    }

    @PackageScope
    int indexOf(Vertex v) {
        vertices.indexOf(v)
    }

    @PackageScope
    void normalizeMissingVertices() {
        for( Edge e : edges ) {
            assert e.from || e.to, 'Missing source and termination vertices for edge'

            if( !e.from ) {
                // creates the missing origin vertex
                def vertex = e.from = new Vertex(Type.ORIGIN)
                int p = vertices.indexOf( e.to )
                vertices.add( p, vertex )
            }
            else if( !e.to ) {
                // creates the missing termination vertex
                def vertex = e.to = new Vertex(Type.NODE)
                int p = vertices.indexOf( e.from )
                vertices.add( p+1, vertex )
            }
        }
    }

    @PackageScope
    void resolveEdgeNames(Map map) {
        edges.each { Edge edge ->
            def name = resolveChannelName(map, edge.channel)
            if( name ) edge.label = name
        }
    }


    @PackageScope
    String resolveChannelName( Map map, DataflowChannel channel ) {
        def entry = map.find { k,v -> v.is channel }
        return entry ? entry.key : null
    }

    @PackageScope
    String getChannelName( ChannelHandler handler ) {
        def result = handler.label
        result ?: (session ? resolveChannelName( session.getBinding().getVariables(), handler.channel ) : null )
    }

    void normalize() {
        normalizeMissingVertices()
        if( session )
            resolveEdgeNames(session.getBinding().getVariables())
        else
            log.debug "Missing session object -- Cannot normalize edge names"
    }


    /**
     * Model a vertex in the DAG.
     *
     * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
     */
    @ToString(includeNames = true, includes = 'label,type', includePackage=false)
    @PackageScope
    class Vertex {

        /**
         * The vertex label
         */
        String label

        /**
         * The vertex type
         */
        Type type

        /**
         * Create an DGA vertex instance
         *
         * @param type A {@link Type} value
         * @param label A descriptive string to label this vertex
         */
        Vertex( Type type, String label = null ) {
            assert type
            this.label = label
            this.type = type
        }

        /**
         * @return The order of the index in the DAG
         */
        int getOrder() {
            indexOf(this)
        }

        /**
         * @return The unique name for this node
         */
        String getName() { "p${getOrder()}" }

    }

    /**
     * Models an edge in the DAG
     *
     * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
     */
    @PackageScope
    @ToString(includeNames = true, includes = 'label,from,to', includePackage=false)
    class Edge {

        /**
         * The {@link groovyx.gpars.dataflow.DataflowChannel} that originated this graph edge
         */
        DataflowChannel channel

        /**
         * The vertex *from* where the edge starts
         */
        Vertex from

        /**
         * The vertex *to* where the edge ends
         */
        Vertex to

        /**
         * A descriptive label
         */
        String label

    }

    /**
     * A simple wrapper object to handle a channel and the associated label
     */
    @ToString(includeNames = true, includes = 'label', includePackage=false)
    static class ChannelHandler {

        /**
         * The {@link groovyx.gpars.dataflow.DataflowChannel} that originated this graph edge
         */
        DataflowChannel channel

        /**
         * The edge label
         */
        String label

    }


}
