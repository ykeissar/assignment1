import com.amazonaws.auth.AWSCredentials;

public class ManagerMainClass {
    public static void main(String[] args) {
        AWSCredentials credentials = new AWSCredentials() {
            public String getAWSAccessKeyId() {
                return null;
            }

            public String getAWSSecretKey() {
                return null;
            }
        };//TODO fix this, WTF
        Manager manager = new Manager(credentials);

        while (manager.shouldTerminate()) {
            //listen to the sqs queue
            String message = manager.readMessagesLookFor("Input_location", manager.getLocalAppQueueUrl());

            //in case we found a message with input location - processing it
            if (message.length() > 0) {//message format - Input_location-Bucket_name %s Key %s
                String bucketName = message.split(" ")[1];
                String key = message.split(" ")[3];
                String fileContent = manager.downloadFile(bucketName, key);
                manager.processInput(fileContent);
            }

        }
    }
}
