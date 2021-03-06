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

package nextflow.executor

import java.nio.file.Paths

import nextflow.processor.TaskConfig
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class PbsExecutorTest extends Specification {

    def testGetCommandLine() {

        given:
        def executor = [:] as PbsExecutor
        expect:
        executor.getSubmitCommandLine(Mock(TaskRun), Paths.get('/some/path/script.sh') ) == ['qsub', 'script.sh']

    }

    def testHeaders() {

        setup:
        def executor = [:] as PbsExecutor

        // mock process
        def proc = Mock(TaskProcessor)

        // task object
        def task = new TaskRun()
        task.processor = proc
        task.workDir = Paths.get('/work/dir')
        task.name = 'the task name'

        when:
        task.config = new TaskConfig()
        then:
        executor.getHeaders(task) == '''
                #PBS -d /work/dir
                #PBS -N nf-the_task_name
                #PBS -o /work/dir/.command.log
                #PBS -j oe
                #PBS -V
                '''
                .stripIndent().leftTrim()


        when:
        task.config = new TaskConfig()
        task.config.queue = 'alpha'
        task.config.time = '1m'
        then:
        executor.getHeaders(task) == '''
                #PBS -d /work/dir
                #PBS -N nf-the_task_name
                #PBS -o /work/dir/.command.log
                #PBS -j oe
                #PBS -V
                #PBS -q alpha
                #PBS -l walltime=00:01:00
                '''
                .stripIndent().leftTrim()


        when:
        task.config = new TaskConfig()
        task.config.queue = 'alpha'
        task.config.time = '1m'
        task.config.memory = '1m'
        then:
        executor.getHeaders(task) == '''
                #PBS -d /work/dir
                #PBS -N nf-the_task_name
                #PBS -o /work/dir/.command.log
                #PBS -j oe
                #PBS -V
                #PBS -q alpha
                #PBS -l walltime=00:01:00
                #PBS -l mem=1mb
                '''
                .stripIndent().leftTrim()



        when:
        task.config = new TaskConfig()
        task.config.queue = 'delta'
        task.config.time = '10m'
        task.config.memory = '5m'
        task.config.cpus = 2
        then:
        executor.getHeaders(task) == '''
                #PBS -d /work/dir
                #PBS -N nf-the_task_name
                #PBS -o /work/dir/.command.log
                #PBS -j oe
                #PBS -V
                #PBS -q delta
                #PBS -l nodes=1:ppn=2
                #PBS -l walltime=00:10:00
                #PBS -l mem=5mb
                '''
                .stripIndent().leftTrim()

        when:
        task.config = new TaskConfig()
        task.config.queue = 'delta'
        task.config.time = '1d'
        task.config.memory = '1g'
        task.config.cpus = 8
        then:
        executor.getHeaders(task) == '''
                #PBS -d /work/dir
                #PBS -N nf-the_task_name
                #PBS -o /work/dir/.command.log
                #PBS -j oe
                #PBS -V
                #PBS -q delta
                #PBS -l nodes=1:ppn=8
                #PBS -l walltime=24:00:00
                #PBS -l mem=1gb
                '''
                .stripIndent().leftTrim()

        when:
        task.config = new TaskConfig()
        task.config.queue = 'delta'
        task.config.time = '2d 6h 10m'
        task.config.memory = '2g'
        then:
        executor.getHeaders(task) == '''
                #PBS -d /work/dir
                #PBS -N nf-the_task_name
                #PBS -o /work/dir/.command.log
                #PBS -j oe
                #PBS -V
                #PBS -q delta
                #PBS -l walltime=54:10:00
                #PBS -l mem=2gb
                '''
                .stripIndent().leftTrim()

    }


    def testParseJobId() {

        given:
        def executor = [:] as PbsExecutor

        expect:
        executor.parseJobId('\n10.localhost\n') == '10.localhost'
        executor.parseJobId('1584288.biocluster.igb.illinois.edu') == '1584288.biocluster.igb.illinois.edu'
    }


    def testKillTaskCommand() {

        given:
        def executor = [:] as PbsExecutor
        expect:
        executor.killTaskCommand('100.localhost') == ['qdel', '100.localhost']

    }

    def testParseQueueStatus() {

        setup:
        def executor = [:] as PbsExecutor
        def text =
                """
                Job Id: 12.localhost
                    job_state = C
                Job Id: 13.localhost
                    job_state = R
                Job Id: 14.localhost
                    job_state = Q
                Job Id: 15.localhost
                    job_state = S
                Job Id: 16.localhost
                    job_state = E
                Job Id: 17.localhost
                    job_state = H

                """.stripIndent().trim()

        when:
        def result = executor.parseQueueStatus(text)
        then:
        result.size() == 6
        result['12.localhost'] == AbstractGridExecutor.QueueStatus.DONE
        result['13.localhost'] == AbstractGridExecutor.QueueStatus.RUNNING
        result['14.localhost'] == AbstractGridExecutor.QueueStatus.PENDING
        result['15.localhost'] == AbstractGridExecutor.QueueStatus.HOLD
        result['16.localhost'] == AbstractGridExecutor.QueueStatus.UNKNOWN
        result['17.localhost'] == AbstractGridExecutor.QueueStatus.HOLD

    }

    def 'should fetch the value' () {
        expect:
        PbsExecutor.fetchValue('Job Id:', 'Job Id:1234') == '1234'
        PbsExecutor.fetchValue('Job Id:', 'Job Id: 1234 ') == '1234'
        PbsExecutor.fetchValue('Job Id:', '  Job Id:  1234') == '1234'
    }

    def 'should return qstat command line' () {
        given:
        def executor = [:] as PbsExecutor

        expect:
        executor.queueStatusCommand(null) == ['sh','-c', "qstat -f -1 | egrep '(Job Id:|job_state =)'"]
        executor.queueStatusCommand('xxx') == ['sh','-c', "qstat -f -1 xxx | egrep '(Job Id:|job_state =)'"]
        executor.queueStatusCommand('xxx').each { assert it instanceof String }
    }

}
