package edu.umd.cs.findbugs.detect;

import edu.umd.cs.findbugs.AnalysisCacheToRepositoryAdapter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.SignatureParser;
import edu.umd.cs.findbugs.bcel.BCELUtil;
import edu.umd.cs.findbugs.classfile.*;
import edu.umd.cs.findbugs.detect.database.*;
import edu.umd.cs.findbugs.detect.database.container.LinkedStack;
import edu.umd.cs.findbugs.util.IDUtils;
import edu.umd.cs.findbugs.util.OpcodeUtils;
import edu.umd.cs.findbugs.util.SignatureUtils;
import org.apache.bcel.classfile.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * @author Peter Yu
 * @date 2018/6/1 17:32
 */
public class BadResourceCheck extends BytecodeScanningDetector {

    public BadResourceCheck(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    public BadResourceCheck(BugReporter bugReporter,
                            Boolean currentLookIntoReturnResource,
                            ResourceInstanceCapturer currentLevelCapturer,
                            Integer currentLevelLastRegLoad,
                            Integer currentScanLevel,
                            Method currentSpecifiedMethod){
        this.bugReporter = bugReporter;
        this.currentLookIntoReturnResource = currentLookIntoReturnResource;
        this.currentLevelCapturer = currentLevelCapturer;
        this.currentLevelLastRegLoad = currentLevelLastRegLoad;
        this.currentScanLevel = currentScanLevel;
        this.currentSpecifiedMethod = currentSpecifiedMethod;
    }

    private IAnalysisCache analysisCache = Global.getAnalysisCache();

    private BugReporter bugReporter;

    private static final Logger LOGGER = Logger.getLogger(BadResourceCheck.class.getName());

    /**
     * 加载所有定义的资源
     */
    private static final Set<Resource> resourceSet = ResourceFactory.listResources();

    private static final ResourceOperationDetector detector = new ResourceOperationDetector();

    /**
     * 通过类全限定名可以获得JavaClass的适配器
     */
    private static final AnalysisCacheToRepositoryAdapter adapter = new AnalysisCacheToRepositoryAdapter();

    /**
     * 用于存储lookInto的方法里面的返回值类型，如果是方法里面的资源类型，则返回，如果不是，返回null
     */
    private Boolean currentLookIntoReturnResource = false;

    private static final LinkedStack<Boolean> lookIntoReturnResourceStack = new LinkedStack<>();

    /**
     * 当前层资源变量存储容器
     */
    private ResourceInstanceCapturer currentLevelCapturer = new ResourceInstanceCapturer();

    /**
     * 存储所有层的lookIntoLevelTempCapturer
     */
    private static final LinkedStack<ResourceInstanceCapturer> capturerStatk = new LinkedStack<>();

    /**
     * 存储aload出来的变量
     */
    private Integer currentLevelLastRegLoad = null;

    private static final LinkedStack<Integer> lastRegLoadStack = new LinkedStack<>();

    /**
     * lookInto时被指定要扫描的方法
     */
    private  Method currentSpecifiedMethod = null;

    /**
     * 用于存放多层lookInto时，指定扫描的方法的栈存储
     */
    private static final LinkedStack<Method> specifiedMethodStack = new LinkedStack<>();

    /**
     * 原始层扫描的标记
     */
    private static final int RAW_SCAN_LEVEL = 0;

    /**
     * lookIntoMethod扫描的标记
     */
    private static final int LOOK_INTO_FOR_OPEN_SCAN_LEVEL = 1;

    private static final int LOOK_INTO_FOR_CLOSE_SCAN_LEVEL = 2;

    private static final String OPEN = "OPEN";

    private static final String CLOSE = "CLOSE";

    private static final String WHITE = "WHITE";

    private static final String BLACK = "BLACK";


    /**
     * 当前扫描层
     */
    private  int currentScanLevel = 0;

    private static final LinkedStack<Integer> scanLevelStack = new LinkedStack<>();

    @Override
    public void visit(Code obj) {
        if(resourceSet.isEmpty()){
            return;
        }
        if(currentSpecifiedMethod != null){
            boolean visitingMethod = visitingMethod();
            try {
                if(getMethod().getName().equals(currentSpecifiedMethod.getName())){
                    super.visit(obj);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }else {
            super.visit(obj);
            // 遍历currentLevelCapturer里面的resourceInstance，将实例的bugInstance报告掉
            LinkedList<ResourceInstance> instances = currentLevelCapturer.listResourceInstance();
            for (ResourceInstance instance : instances) {
                bugReporter.reportBug(instance.getBugInstance());
            }
            currentLevelCapturer.clear();
        }
    }

    @Override
    public void sawOpcode(int seen) {

        // 如果是最原始的代码扫描层，使用这套逻辑
        if(currentScanLevel == RAW_SCAN_LEVEL){

            if(seen == ASTORE||seen == ASTORE_0||seen == ASTORE_1||seen == ASTORE_2||seen == ASTORE_3){
                int registerOperand = getRegisterOperand();
                boolean addFlag = currentLevelCapturer.addStackIndex(registerOperand);
            }

            if(seen == ALOAD||seen == ALOAD_0||seen == ALOAD_1||seen == ALOAD_2||seen == ALOAD_3){
                currentLevelLastRegLoad = getRegisterOperand();
            }

            if(seen == ARETURN){
                // 判断返回的对象是不是开启的资源对象，通过变量的stackIndex（registerOperand）进行判断，
                // 如果是，将其从capturer中消除（相当于关闭资源）
                int prevOpcode = getPrevOpcode(1);
                boolean isLoad = OpcodeUtils.isLoad(prevOpcode);
                if (isLoad) {
                    currentLevelCapturer.removeInstance(currentLevelLastRegLoad);
                }
            }

            if(seen == INVOKEVIRTUAL||seen == INVOKESPECIAL||seen == INVOKESTATIC||seen == INVOKEINTERFACE){
                // 初始化指令码操作对象
                String classConstantOperand = getClassConstantOperand();
                String nameConstantOperand = getNameConstantOperand();
                String signature = getMethodDescriptorOperand().getSignature();
                ResourceOperation targetOperation = new ResourceOperation(classConstantOperand, nameConstantOperand,signature);


                // 如果是开启资源的方法，则建立资源实例，存储到ResourceInstanceCapturer当中去
                boolean resourceOpen = isResourceOpenInvoke(targetOperation);
                if(resourceOpen){
                    int nextOpcode = getNextOpcode();
                    // (加一个前提条件后面的一个操作码不是ARETURN)
                    if(nextOpcode != ARETURN){
                        BugInstance bugInstance = new BugInstance(this,"RESOURCE_NOT_RELEASED",HIGH_PRIORITY)
                                .addClassAndMethod(this).addSourceLine(this,getPC());
                        ResourceInstance resourceInstance = new ResourceInstance(targetOperation.getInvolvedResourceForOpenInvoke(), null, bugInstance);
                        // 并且要把ResourceInstanceCapturer的valve打开，方便下个指令码扫描时，加入stackIndex
                        currentLevelCapturer.addInstance(resourceInstance);
                    }

                    // 如果判定是Open方法,加入白名单中
                    detector.appendOperation(targetOperation, OPEN, WHITE);
                    return;
                }else {
                    // 如果判定不是Open方法，加入黑名单中
                    detector.appendOperation(targetOperation, OPEN, BLACK);
                }


                // 如果是关闭资源的方法，则将资源从ResourceInstanceCapturer中去除，需要知道变量的statckIndex
                boolean resourceClose = isResourceCloseInvoke(targetOperation);
                if(resourceClose){
                    currentLevelCapturer.removeInstance(currentLevelLastRegLoad);
                    currentLevelLastRegLoad = null;

                    // 如果判定是Close方法,加入白名单中
                    detector.appendOperation(targetOperation, CLOSE, WHITE);
                }else {
                    // 如果判定不是Close方法,加入白名单中
                    detector.appendOperation(targetOperation, CLOSE, BLACK);
                }

                return;
            }
        }

        // 如果是深入扫描指定的某个方法的内部寻找资源Open相关信息，则使用这套逻辑
        if(currentScanLevel == LOOK_INTO_FOR_OPEN_SCAN_LEVEL){

            if(seen == ASTORE||seen == ASTORE_0||seen == ASTORE_1||seen == ASTORE_2||seen == ASTORE_3){
                int registerOperand = getRegisterOperand();
                boolean addFlag = currentLevelCapturer.addStackIndex(registerOperand);
            }

            if(seen == ALOAD||seen == ALOAD_0||seen == ALOAD_1||seen == ALOAD_2||seen == ALOAD_3){
                currentLevelLastRegLoad = getRegisterOperand();
            }

            if(seen == ARETURN){
                // 判断返回的对象是不是开启的资源对象，通过变量的stackIndex（registerOperand）进行判断，
                // 如果是，将资源类型存到lookIntoReturnResource里面
                int prevOpcode = getPrevOpcode(1);
                boolean isLoad = OpcodeUtils.isLoad(prevOpcode);
                if(isLoad){
                    currentLookIntoReturnResource  = currentLevelCapturer.removeInstance(currentLevelLastRegLoad);
                    currentLevelLastRegLoad = null;
                }
            }

            // 如果在本层发现有资源创建，并作为返回值，则说明有方法，需要返回true，
            // 这个true存在哪里好呢,存在lookIntoResultMap里面
            if(seen == INVOKEVIRTUAL||seen == INVOKESPECIAL||seen == INVOKESTATIC||seen == INVOKEINTERFACE){
                // 初始化指令码操作对象
                String classConstantOperand = getClassConstantOperand();
                String nameConstantOperand = getNameConstantOperand();
                String signature = getMethodDescriptorOperand().getSignature();
                ResourceOperation targetOperation = new ResourceOperation(classConstantOperand, nameConstantOperand,signature);

                boolean resourceOpen = isResourceOpenInvoke(targetOperation);
                boolean resourceClose = isResourceCloseInvoke(targetOperation);
                if(resourceOpen){
                    // 需要将资源对象存起来，等到方法走到返回的地方时
                    // 查看返回的对象statckIndex是否和记录下来的资源对象的statckIndex相匹配
                    // 如果相匹配，将资源对象的种类Resource存起来，怎么存？用栈结构存储
                    // 如果nextOpcode是ARETURN，则直接将Resource赋值给lookIntoResource
                    int nextOpcode = getNextOpcode();
                    if(nextOpcode != ARETURN){
                        ResourceInstance instance = new ResourceInstance(targetOperation.getInvolvedResourceForOpenInvoke(), null, null);
                        currentLevelCapturer.addInstance(instance);
                    }else {
                        currentLookIntoReturnResource = resourceOpen;
                    }

                    // 加入白名单
                    detector.appendOperation(targetOperation, OPEN, WHITE);
                    return;
                }else {
                    // 加入黑名单
                    detector.appendOperation(targetOperation, OPEN, BLACK);
                }

                if (resourceClose) {
                    currentLevelCapturer.removeInstance(currentLevelLastRegLoad);
                    currentLevelLastRegLoad = null;

                    // 加入白名单
                    detector.appendOperation(targetOperation, CLOSE, WHITE);
                }else {
                    detector.appendOperation(targetOperation, CLOSE, BLACK);
                }

                return;
            }
        }

        // 如果是深入扫描指定的某个方法的内部寻找资源Close相关信息，则使用这套逻辑
        if(currentScanLevel == LOOK_INTO_FOR_CLOSE_SCAN_LEVEL){

            if(seen == ALOAD||seen == ALOAD_0||seen == ALOAD_1||seen == ALOAD_2||seen == ALOAD_3){
                currentLevelLastRegLoad = getRegisterOperand();
            }

            if(seen == INVOKEVIRTUAL||seen == INVOKESPECIAL||seen == INVOKESTATIC||seen == INVOKEINTERFACE){
                // 初始化指令码操作对象
                String classConstantOperand = getClassConstantOperand();
                String nameConstantOperand = getNameConstantOperand();
                String signature = getMethodDescriptorOperand().getSignature();
                ResourceOperation targetOperation = new ResourceOperation(classConstantOperand, nameConstantOperand,signature);

                boolean resourceClose = isResourceCloseInvoke(targetOperation);
                if (resourceClose) {
                    // 如果lastRegLoad=1，说明关闭的是参数里面的资源
                    if(currentSpecifiedMethod.isStatic()){
                        if(currentLevelLastRegLoad == 0){
                            currentLookIntoReturnResource = resourceClose;
                        }
                    }else {
                        if(currentLevelLastRegLoad == 1){
                            currentLookIntoReturnResource = resourceClose;
                        }
                    }

                    // 加入Close白名单
                    detector.appendOperation(targetOperation, CLOSE, WHITE);
                }
            }
        }
    }


    // 思路：
    // 返回值必须匹配，相同或者是Resource的父类
    /**
     *
     * @param targetOperation 目标操作
     * @return
     */
    private boolean likeResourceOpenInvoke(ResourceOperation targetOperation)  {
        // 从signature里面取到返回类型的部分
        String signature = targetOperation.getSignature();
        String className = SignatureUtils.getObjectReturnTypeClassName(signature);
        if(className == null){
            return false;
        }
        ResourceMacher resourceMacher = new ResourceMacher(className);
        return resourceMacher.matches();
    }

    // 思路：
    // 如果signature是以（资源）V形式存在，则可能是关闭资源的方法，
    // 并且参数里面的资源是之前已经Open过的
    /**
     * 判断指令操作想不想资源关闭的操作
     * @param targetOperation
     * @return
     */
    private boolean likeResourceCloseInvoke(ResourceOperation targetOperation){

        String methodName = targetOperation.getMethodName();
        if("<init>".equals(methodName)){
            return false;
        }
        String signature = targetOperation.getSignature();
        SignatureParser parser = new SignatureParser(signature);
        String returnTypeSignature = parser.getReturnTypeSignature();
        // 如果不是void函数，返回false
        if(!"V".equals(returnTypeSignature)){
            return false;
        }

        // 遍历参数数组里面是否有资源类型的参数
        String[] arguments = parser.getArguments();
        if(arguments.length != 1){
            return false;
        }
        String argument = arguments[0];
        String className = SignatureUtils.getObjectParamClassName(argument);
        if (className != null) {
            ResourceMacher macher = new ResourceMacher(className);
            return macher.matches();
        }
        return false;
    }

    /**
     * 判断指令是否是用于开启资源的
     *
     * @return
     */
    private boolean isResourceOpenInvoke(ResourceOperation targetOperation){
        // 如果在目前已有的方法库里面能够匹配到，则返回true，不然继续进一步判断
        boolean inOpenWhiteList = detector.inNameList(targetOperation, OPEN, WHITE);
        if(inOpenWhiteList){
            return true;
        }
        boolean inOpenBlackList = detector.inNameList(targetOperation, OPEN, BLACK);
        if(inOpenBlackList){
            return false;
        }

        // 如果不像是资源开启方法，直接返回false
        if(likeResourceOpenInvoke(targetOperation)){
            return lookIntoMethodWraper(targetOperation,LOOK_INTO_FOR_OPEN_SCAN_LEVEL);
        }
        return false;
    }

    /**
     * 判断方法
     * @param targetOperation
     * @return
     */
    private boolean isResourceCloseInvoke(ResourceOperation targetOperation){
        // 如果在目前已有的方法库里面能够匹配到，则返回true，不然继续进一步判断
        boolean inCloseWhiteList = detector.inNameList(targetOperation, CLOSE, WHITE);
        if(inCloseWhiteList){
            return true;
        }
        boolean inCloseBlackList = detector.inNameList(targetOperation, CLOSE, BLACK);
        if(inCloseWhiteList){
            return false;
        }

        // 思路同Open方法
        if(likeResourceCloseInvoke(targetOperation)){
            return lookIntoMethodWraper(targetOperation,LOOK_INTO_FOR_CLOSE_SCAN_LEVEL);
        }
        return false;
    }

    /**
     * 对lookIntoMethod做了栈操作的增强
     * @param targetOperation
     * @param scanLevel
     * @return
     */
    private boolean lookIntoMethodWraper(ResourceOperation targetOperation, int scanLevel) {
        // 到方法内部看看，如果里面存在创建资源，且将资源作为返回结果的，则返回对应资源的种类，不然返回null
        // lookInto之前，要将lookIntoLevelTempCapturer以及currentLevelLastRegLoads进行入栈操作，
        capturerStatk.push(currentLevelCapturer);
        lastRegLoadStack.push(currentLevelLastRegLoad);
        lookIntoReturnResourceStack.push(currentLookIntoReturnResource);
        specifiedMethodStack.push(currentSpecifiedMethod);
        scanLevelStack.push(currentScanLevel);

        boolean resource = false;
        try {
            resource = lookIntoMethod(targetOperation, scanLevel);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {

            // 并在lookInto方法里面操作完毕后进行出栈操作
            currentLevelCapturer = capturerStatk.pop();
            currentLevelLastRegLoad = lastRegLoadStack.pop();
            currentLookIntoReturnResource = lookIntoReturnResourceStack.pop();
            currentSpecifiedMethod = specifiedMethodStack.pop();
            currentScanLevel = scanLevelStack.pop();
        }

        return resource;
    }

    /**
     * 用于检查方法里面有无创建资源并返回，如果有，返回true
     * @param targetOperation 目标检测操作
     * @param scanLevel
     * @return
     */
    // lookInto之后，currentCapture要重新建立，lookInto结束之后，当前的currentCapture要销毁
    private boolean lookIntoMethod(ResourceOperation targetOperation, Integer scanLevel) {
        currentLevelCapturer = new ResourceInstanceCapturer();
        currentLevelLastRegLoad = null;
        currentLookIntoReturnResource = null;
        try {
            JavaClass aClass = adapter.findClass(targetOperation.getClazzName());
            ClassDescriptor classDescriptor = DescriptorFactory.createClassDescriptor(aClass);
            ClassContext classContext = analysisCache.getClassAnalysis(ClassContext.class, classDescriptor);
            Method[] methods = aClass.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if(method.getName().equals(targetOperation.getMethodName())){
                    Code lookIntoCode = method.getCode();
                    currentScanLevel = scanLevel;
                    currentSpecifiedMethod = method;
                    BadResourceCheck badResourceCheck = new BadResourceCheck(bugReporter,
                                                                             currentLookIntoReturnResource,
                                                                             currentLevelCapturer,
                                                                             currentLevelLastRegLoad,
                                                                             currentScanLevel,
                                                                             currentSpecifiedMethod);
                    badResourceCheck.visitClassContext(classContext);


                    // 如果是满足条件的资源开启或者关闭操作，则返回这个资源类型Resource
                    return badResourceCheck.currentLookIntoReturnResource;
                }
            }
            return false;
        } catch (Exception e) {
            LOGGER.warning("Check skipped! Class file not found:"+targetOperation.getClazzName()+".");
        }
        return false;
    }
}
