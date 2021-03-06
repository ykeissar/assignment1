import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import org.apache.commons.codec.binary.Base64;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LocalApp {
    private static final String IAM_ARN = "arn:aws:iam::592374997611:instance-profile/admin";
    private static final String AMI = "ami-00221e3ef03dfd01b";
    private static final String KEY_PAIR = "my_key3";
    private static final String MANAGER_QUEUE = "LocalApp-Manager-Input";
    private static final String INSTANCE_TYPE = InstanceType.T3Xlarge.toString();
    private AmazonS3 s3;
    private AmazonEC2 ec2;
    private AWSCredentialsProvider credentialsProvider;
    private String bucketName = null;
    private AmazonSQS sqs;
    private String sendingQueueUrl = null;
    private String receivingQueueUrl;
    private int workerMessageRatio;
    private int queueId;

    public LocalApp(int workerMessageRatio) {
        try {
            credentialsProvider = new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());

            s3 = AmazonS3ClientBuilder.standard()
                    .withCredentials(credentialsProvider)
                    .withRegion(Regions.US_WEST_2)
                    .build();
            ec2 = AmazonEC2ClientBuilder.standard()
                    .withCredentials(credentialsProvider)
                    .withRegion(Regions.US_WEST_2)
                    .build();

            sqs = AmazonSQSClientBuilder.standard()
                    .withCredentials(credentialsProvider)
                    .withRegion(Regions.US_WEST_2)
                    .build();

            this.workerMessageRatio = workerMessageRatio;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    public String getReceivingQueueUrl() {
        return receivingQueueUrl;
    }

    public int getQueueId() {
        return queueId;
    }

    public void terminate() {
        System.out.println("Shutting down...");
    }

    public String getBucketName() {
        return bucketName;
    }

    public void toHtml(String content, String address) {
        System.out.println("Creating html file from output");
        String html = new StringBuilder().append("<!DOCTYPE html>\n")
                .append("<html>\n")
                .append("<body>\n")
                .append(content)
                .append("</body>\n")
                .append("</html>\n").toString();
        File toReturn = new File(address);
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(toReturn));
            writer.write(html);
            writer.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    //----------------------------------EC2---------------------------------
    public Instance getManagerInstance() {
        try {
            System.out.println("Fetching Manager node.");
            boolean done = false;
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            while (!done) {
                DescribeInstancesResult response = ec2.describeInstances(request);
                for (Reservation reservation : response.getReservations()) {
                    if (!reservation.getInstances().isEmpty()) {
                        for (Instance instance : reservation.getInstances()) {
                            if (!instance.getTags().isEmpty() && instance.getTags().get(0).getKey().equals("App") && instance.getTags().get(0).getValue().equals("Manager") && instance.getState().getName().equals("running"))
                                return instance;
                        }
                    }

                    request.setNextToken(response.getNextToken());

                    if (response.getNextToken() == null) {
                        done = true;
                    }
                }
            }
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
        return null;
    }

    public void startManager(int workerMessageRatio) {
        try {
            RunInstancesRequest request = new RunInstancesRequest(AMI, 1, 1);
            request.setInstanceType(INSTANCE_TYPE);
            request.setKeyName(KEY_PAIR);

            sendingQueueUrl = createManagerQueue();

            IamInstanceProfileSpecification iam = new IamInstanceProfileSpecification();
            iam.setArn(IAM_ARN);
            request.setIamInstanceProfile(iam);

            String workerRatio = Integer.toString(workerMessageRatio);
            String bootstrapManager = new StringBuilder()
                    .append("#! /bin/bash\n")
                    .append("cd /home/ec2-user\n")
                    .append("aws s3 cp s3://yoavsbucke83838/manager.jar manager.jar\n")
                    .append("java -jar manager.jar ")
                    .append(sendingQueueUrl)
                    .append(" ")
                    .append(workerRatio)
                    .toString();

            String base64BootstrapManager = null;
            try {
                base64BootstrapManager = new String(Base64.encodeBase64(bootstrapManager.getBytes("UTF-8")), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            request.setUserData(base64BootstrapManager);
            System.out.println("Starting Manager node.");
            Instance i = ec2.runInstances(request).getReservation().getInstances().get(0);

            List<String> ids = new ArrayList<String>();
            ids.add(i.getInstanceId());

            List<Tag> tags = new ArrayList<Tag>();
            tags.add(new Tag("App", "Manager"));
            CreateTagsRequest tagsRequest = new CreateTagsRequest(ids, tags);
            ec2.createTags(tagsRequest);
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    public void terminateManager() {
        //send termination message to Manager
        System.out.println("Terminating Manager node.");
        sendMessage("Terminate");
        while (true) {
            if (readMessagesLookFor("Terminating done!", sendingQueueUrl) != null)
                break;
        }
        //terminate manager node
        Instance manager = getManagerInstance();
        TerminateInstancesRequest request = new TerminateInstancesRequest();
        List<String> ids = new ArrayList<String>();
        ids.add(manager.getInstanceId());
        request.setInstanceIds(ids);
        boolean stopped = false;
        System.out.println("Trying to stop manager.");
        while (!stopped) {
            try {
                ec2.terminateInstances(request);
                stopped = true;
            } catch (AmazonServiceException ase) {
            }
        }

        sqs.deleteQueue(new DeleteQueueRequest(sendingQueueUrl));
        System.out.println("Deleting queue " + sendingQueueUrl);
        s3.deleteBucket(bucketName);
        System.out.println("Deleting bucket " + bucketName);
    }

    //----------------------------------S3----------------------------------

    public String uploadFile(File file) {
        try {
            if (bucketName == null) {
                bucketName = credentialsProvider.getCredentials().getAWSAccessKeyId().replace('/', '_').replace(':', '_').toLowerCase();
                System.out.println(String.format("Creating bucket %s.", bucketName));
                try {
                    s3.createBucket(bucketName);
                } catch (AmazonServiceException ase) {
                    System.out.println("Caught Exception: " + ase.getMessage());
                }

            }
            String key = file.getName().replace('\\', '_').replace('/', '_').replace(':', '_');
            PutObjectRequest req = new PutObjectRequest(bucketName, key, file);
            s3.putObject(req);
            return key;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
        return null;
    }

    public String downloadFile(String key) {
        try {
            System.out.println(new StringBuilder("Downloading an object from bucket name - ").append(bucketName).append(",").append("key - ").append(key).toString());
            S3Object object = s3.getObject(new GetObjectRequest(bucketName, key));
            S3ObjectInputStream inputStream = object.getObjectContent();

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String text = "";
            String temp = "";

            try {
                while ((temp = bufferedReader.readLine()) != null) {
                    text = text + temp;
                }
                bufferedReader.close();
                inputStream.close();
            } catch (IOException e) {
                System.out.println(new StringBuilder("Exception while downloading from key")
                        .append(key)
                        .append(" with reading buffer, Error: ")
                        .append(e.getMessage())
                        .toString());
            }

            return text;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
        return null;
    }

    public void deleteObject(String key) {

        s3.deleteObject(bucketName, key);
    }

    //----------------------------------SQS---------------------------------

    public String createManagerQueue() {
        try {
            CreateQueueRequest createQueueRequest = new CreateQueueRequest(MANAGER_QUEUE);
            String myQueueUrl = sqs.createQueue(createQueueRequest).getQueueUrl();
            System.out.println(String.format("Creating Sqs queue with url - %s.", myQueueUrl));
            return myQueueUrl;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
        return "";
    }

    public String createQueue() {
        try {
            CreateQueueRequest createQueueRequest = new CreateQueueRequest("Local-app_Manager-Output" + UUID.randomUUID());
            String myQueueUrl = sqs.createQueue(createQueueRequest).getQueueUrl();
            System.out.println(String.format("Creating Sqs queue with url - %s.", myQueueUrl));
            return myQueueUrl;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
        return "";
    }

    public void sendMessage(String message) {
        try {
            sqs.sendMessage(new SendMessageRequest(sendingQueueUrl, message));
            System.out.println(new StringBuilder("Sending message '")
                    .append(message)
                    .append("' to queue with url - ")
                    .append(sendingQueueUrl)
                    .toString());
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
    }

    public Message readMessagesLookFor(String lookFor, String queueUrl) {
        try {
            ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(queueUrl);
            receiveMessageRequest.withWaitTimeSeconds(5);
            receiveMessageRequest.setVisibilityTimeout(0);
            List<Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();
            for (Message message : messages) {
                if (message.getBody().contains(lookFor)) {
                    return message;
                }
            }
            return null;
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }
        return null;

    }

    public void deleteMessage(Message message, String queueUrl) {
        sqs.deleteMessage(new DeleteMessageRequest(queueUrl, message.getReceiptHandle()));
    }

    public void setManagerQueue() {
        try {
            GetQueueUrlResult result = sqs.getQueueUrl(MANAGER_QUEUE);
            sendingQueueUrl = result.getQueueUrl();
        } catch (AmazonServiceException ase) {
            startManager(workerMessageRatio);
        }
    }

    public void startConnection() {
        receivingQueueUrl = createQueue();
        sendMessage("New_Local_App " + receivingQueueUrl);
        while (true) {
            Message message = readMessagesLookFor("This_is_ID", receivingQueueUrl);
            if (message != null) {
                String content = message.getBody();
                String id = content.substring(10);
                queueId = Integer.parseInt(id);
                deleteMessage(message, receivingQueueUrl);
                break;
            }
        }
    }
}
