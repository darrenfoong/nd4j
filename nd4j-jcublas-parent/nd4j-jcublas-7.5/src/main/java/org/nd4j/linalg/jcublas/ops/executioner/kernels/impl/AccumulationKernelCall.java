package org.nd4j.linalg.jcublas.ops.executioner.kernels.impl;

import jcuda.Pointer;
import org.nd4j.linalg.api.blas.BlasBufferUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.*;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.gpumetrics.GpuMetrics;
import org.nd4j.linalg.jcublas.kernel.KernelFunctions;
import org.nd4j.linalg.jcublas.ops.executioner.kernels.BaseGpuKernelCall;
import org.nd4j.linalg.jcublas.util.KernelParamsWrapper;
import org.nd4j.linalg.jcublas.util.PointerUtil;
import org.nd4j.linalg.util.ArrayUtil;

import java.util.Arrays;

/**
 * Kernel call
 *  for accumulation
 *
 * @author Adam Gibson
 */
public class AccumulationKernelCall extends BaseGpuKernelCall {
    protected int[] dimension;
    protected int xStride;
    protected int yStride;
    protected boolean scalarResult;
    protected int[] multiDimension;
    protected int resultIndex,extraParamsIndex;

    public final static String POST_PROCESS_NAME = "postProcessLoop";

    /**
     * Accumulation kernel call
     * @param op the op to use
     * @param dimension the dimensions for reduction
     */
    public AccumulationKernelCall(Op op,int[] dimension) {
        super(op);
        if(dimension == null)
            dimension = new int[] {Integer.MAX_VALUE};
        this.dimension = dimension;
        if(dimension[0] == Integer.MAX_VALUE) {
            scalarResult = true;
        }

        if(dimension.length > 1) {
            if(dimension.length == op.x().rank()) {
                this.dimension = new int[] {Integer.MAX_VALUE};
                scalarResult = true;
            }
            else {
                //the dimensions need to be in order
                Arrays.sort(dimension);
                this.multiDimension = dimension;
                //switch it to be being only the last dimension
                //the tad will be the prod of the previous dimensions
                this.dimension = new int[] {dimension[dimension.length - 1]};
            }

        }

        if(scalarResult)
            op.setZ(Nd4j.create(metrics.getGridSize()));
        else {
            op.setZ(Nd4j.create(ArrayUtil.removeIndex(op.x().shape(),dimension)));
        }

        createArgs();

    }


    public void multiReduce() {
        int lengthDelta = op.x().tensorssAlongDimension(dimension) / op.x().tensorssAlongDimension(multiDimension);
        //the shape of the new collapsed result
        INDArray collapsedResult = Nd4j.create(ArrayUtil.removeIndex(op.x().shape(),multiDimension));
        Accumulation acc = (Accumulation) op;
        for(int i = 0; i < acc.z().length(); i++) {
            collapsedResult.putScalar(i % lengthDelta,acc.combineSubResults(collapsedResult.getDouble(i % lengthDelta),op.z().getDouble(i)));
        }

        for(int i = 0; i < collapsedResult.length(); i++) {
            ((Accumulation) op).setFinalResult(collapsedResult.getDouble(i));
            collapsedResult.putScalar(i,acc.getAndSetFinalResult(collapsedResult.getDouble(i)));
        }

    }




    @Override
    public void createMetrics() {
        String functionName = op instanceof TransformOp || op instanceof Accumulation || op instanceof IndexAccumulation ? op.name() + "_strided" : op.name();

        GpuMetrics metrics = GpuMetrics.blocksAndThreadsOccupancy(functionName, getType(op), op.n());
        if (dimension != null && dimension.length >= 1 && dimension[0] != Integer.MAX_VALUE) {
            int length = op.x().tensorssAlongDimension(dimension);
            if (length > 1000)
                length = 1000;
            int sharedMemBasedOnBlockSize = op.x().tensorAlongDimension(0, dimension).length() * 10 * op.x().data().getElementSize();
            if (sharedMemBasedOnBlockSize < 1024)
                sharedMemBasedOnBlockSize = 1024;
            metrics.setSharedMemoryNotOverMax(sharedMemBasedOnBlockSize);
        } else {
            int sharedMemBasedOnBlockSize = op.n() * op.x().data().getElementSize();
            if (sharedMemBasedOnBlockSize < 1024)
                sharedMemBasedOnBlockSize = 1024;
            metrics.setSharedMemoryNotOverMax(sharedMemBasedOnBlockSize);
            //setup a number of threads = the number of blocks being launched
        }

        this.metrics = metrics;


    }

    @Override
    public void createCudaConext() {

    }

    @Override
    public void createArgs() {
        if (op.y() != null) {
            metrics.setSharedMemoryNotOverMax(metrics.getSharedMemory() * 2);
            xStride = BlasBufferUtil.getBlasStride(dimension == null ? op.x() : op.x().tensorAlongDimension(0, dimension));
            if (xStride < 0) {
                op.setX(op.x().dup());
                xStride = BlasBufferUtil.getBlasStride(dimension == null ? op.x() : op.x().tensorAlongDimension(0, dimension));
                if (xStride < 0)
                    throw new IllegalStateException("Unable to compute element wise stride");

            }

            yStride = BlasBufferUtil.getBlasStride(dimension == null ? op.y() : op.y().tensorAlongDimension(0, dimension));
            if (op.y().ordering() != op.x().ordering()) {
                op.setY(op.y().dup(op.x().ordering()));
                yStride = BlasBufferUtil.getBlasStride(dimension == null ? op.y() : op.y().tensorAlongDimension(0, dimension));
                if (yStride < 0)
                    throw new IllegalStateException("Unable to compute element wise stride");

            }

            //result index for the pointer to use when invoking the post process method
            resultIndex = 6;
            extraParamsIndex = 5;
            args = new Object[] {
                    op.n(),
                    op.x(),
                    KernelFunctions.alloc(PointerUtil.toShapeInfoBuffer(op.x(), dimension)),
                    op.y(),
                    KernelFunctions.alloc(PointerUtil.toShapeInfoBuffer(op.y(), dimension)),
                    toArgs(op.extraArgs(),
                            getType(op)),
                    op.z(),
                    KernelFunctions.alloc(PointerUtil.toShapeInfoBuffer(op.z())),
                    KernelFunctions.alloc(metrics.getGpuDefinitionInfo()),
                    KernelFunctions.alloc(dimension == null ? new int[]{Integer.MAX_VALUE} : dimension),
                    dimension == null ? 1 : dimension.length,
                    //if the whole buffer is to be used don't do final aggregation this happens
                    //by aggregating blocks on cpu first
                    toInt((dimension == null || dimension[0] == Integer.MAX_VALUE))

            };


        } else {
            INDArray firstTad = null;
            //handle case where the tad is actually the whole array
            if (!scalarResult) {
                firstTad = op.x().tensorAlongDimension(0, dimension);
                if (firstTad.length() == op.x().length())
                    dimension = null;
            }

            xStride = BlasBufferUtil.getBlasStride(scalarResult ? op.x() : firstTad);
            if (xStride < 0) {
                op.setX(op.x().dup());
                xStride = BlasBufferUtil.getBlasStride(scalarResult ? op.x() : firstTad);
                //dup didn't handle it
                if (xStride < 0) {
                    throw new IllegalStateException("Unable to compute element wise stride for x");}
            }

            int sharedMemBasedOnBlockSize = op.n() * op.x().data().getElementSize();
            if (sharedMemBasedOnBlockSize < 1024)
                sharedMemBasedOnBlockSize = 1024;
            metrics.setSharedMemoryNotOverMax(sharedMemBasedOnBlockSize);


            int length = op.x().data().length();
            if (dimension == null && xStride == 1 && op.x().offset() == 0)
                length = op.n();
            //result index for the pointer to use when invoking the post process method
            resultIndex = 4;
            extraParamsIndex = 3;
            args = new Object[] {
                    length,
                    op.x(),
                    KernelFunctions.alloc(PointerUtil.toShapeInfoBuffer(op.x(), dimension)),
                    toArgs(op.extraArgs(), getType(op)),
                    op.z(),
                    KernelFunctions.alloc(PointerUtil.toShapeInfoBuffer(op.z())),
                    KernelFunctions.alloc(metrics.getGpuDefinitionInfo()),
                    KernelFunctions.alloc(scalarResult ? new int[]{Integer.MAX_VALUE} : dimension),
                    scalarResult ? 1 : dimension.length,
                    //if the whole buffer is to be used don't do final aggregation this happens
                    //by aggregating blocks on cpu first
                    toInt(scalarResult)
            };
        }
    }



    /**
     * Calculates a reduction across blocks
     * @param op
     * @param resultAcrossBlocks
     */
    public static  void calculateBlockResult(Accumulation op,INDArray resultAcrossBlocks) {
        int oldN = op.n();
        INDArray oldX = op.x();
        op.setX(resultAcrossBlocks);
        op.setApplyFinalTransform(false);
        double result = op.zeroDouble();
        for(int i = 0; i < resultAcrossBlocks.length(); i++) {
            double firstVal = resultAcrossBlocks.data().getDouble(resultAcrossBlocks.offset() + i * resultAcrossBlocks.elementWiseStride());
            result = op.combineSubResults(firstVal,result);
        }

        if(resultAcrossBlocks.length() == 1)
            result = resultAcrossBlocks.getDouble(0);

        op.setFinalResult(result);
        op.setApplyFinalTransform(true);
        op.setN(oldN);
        op.setX(oldX);
        op.getAndSetFinalResult(op.getFinalResult().doubleValue());
    }



    @Override
    public void invoke() {
        Accumulation acc = (Accumulation) op;
        try(KernelParamsWrapper kParams = new KernelParamsWrapper(true,args).setResultOp(acc, op.z(),dimension)) {
            //setup the kernel parameters such that super.invoke() will call the kernel with the given parameters
            this.args = kParams.getKernelParameters();
            this.cudaContext = kParams.getContext();
            boolean collapseTad = dimension.length > 1;
            int[] oldDimensionTemp = this.dimension;
            //no element wise stride
            if(dimension.length > 1) {
                //reduce on smaller dimension
                this.dimension = new int[] {dimension[dimension.length]};
            }

            //invoke basic reduce
            super.invoke();
            //invoke the collapse tad
            if(collapseTad) {
                TadCollapseAccumulation collapseAccumulation = new TadCollapseAccumulation(this.op,oldDimensionTemp,this.dimension);
                Nd4j.getExecutioner().exec(collapseAccumulation);
            }

            //now switch back for original problem
            this.dimension = oldDimensionTemp;

            //collapse dimension result
            //dimension result
            if(dimension != null && dimension[0] != Integer.MAX_VALUE) {
                Object[] newArgs = new Object[] {
                        op.x().tensorAlongDimension(0,dimension).length(),
                        op.x().offset(),
                        (Pointer) this.args[resultIndex],
                        op.x().tensorAlongDimension(0,dimension).elementWiseStride(),
                        (Pointer) this.args[extraParamsIndex],
                        (Pointer) this.args[resultIndex],
                };

                String functionName = op instanceof TransformOp || op instanceof Accumulation || op instanceof IndexAccumulation ? op.name() + "_strided" : op.name();

                KernelFunctions.invoke(
                        metrics
                        ,true
                        ,functionName
                        ,POST_PROCESS_NAME + "_" + getType(op)
                        ,getType(op)
                        ,cudaContext,newArgs);
            }
        } catch(Exception e) {
            throw new RuntimeException("Could not execute kernel", e);
        }

    }




    private int toInt(boolean val) {
        return val ? 1 : 0;
    }

}