import * as cdk from 'aws-cdk-lib/core';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as subscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import { Construct } from 'constructs';

export class MyNotificationsStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // SNS topic for email notifications
    const topic = new sns.Topic(this, 'NotificationsTopic', {
      topicName: 'my-notifications-topic',
      displayName: 'My Notifications',
    });

    // Subscribe email address to the topic
    topic.addSubscription(
      new subscriptions.EmailSubscription('anandmaheshwari005@gmail.com')
    );

    // Output the topic ARN for reference
    new cdk.CfnOutput(this, 'TopicArn', {
      value: topic.topicArn,
      description: 'SNS Topic ARN',
    });
  }
}
